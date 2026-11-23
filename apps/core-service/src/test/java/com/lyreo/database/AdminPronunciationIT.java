package com.lyreo.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lyreo.ai.application.admin.AiAdminService;
import com.lyreo.ai.infrastructure.AiConfiguration;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.api.ShadowingReference;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import com.lyreo.platform.jobs.infrastructure.BackgroundJobWorker;
import com.lyreo.platform.jobs.infrastructure.JobsConfiguration;
import com.lyreo.platform.storage.LocalFileObjectStorageAdapter;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.speechassessment.application.AdminPronunciationAttempts;
import com.lyreo.speechassessment.application.RecordingLimits;
import com.lyreo.speechassessment.infrastructure.AssessmentAudio;
import com.lyreo.speechassessment.infrastructure.persistence.JdbcAdminPronunciationAttempts;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = AdminPronunciationIT.Config.class, properties = {
    "lyreo.ai-service.internal-token=assessment-it-token",
    "lyreo.ai-service.confirmed-free-providers=AZURE_SPEECH",
    "lyreo.security.master-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class AdminPronunciationIT {
    private static final String SMOKE_URL = System.getenv("LYREO_ASSESSMENT_SMOKE_URL");
    private static final boolean LIVE = SMOKE_URL != null;
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");
    static {
        postgres.start();
    }
    static HttpServer fakeAi;
    static Path storageRoot;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(RecordingLimits.class)
    @Import({AiConfiguration.class, JobsConfiguration.class, AdminPronunciationAttempts.class,
        JdbcAdminPronunciationAttempts.class, AssessmentAudio.class})
    static class Config {
        @Bean ShadowingReference reference() {
            return mock(ShadowingReference.class);
        }

        @Bean ObjectStoragePort storage() throws IOException {
            storageRoot = Files.createTempDirectory("assessment-it-");
            return new LocalFileObjectStorageAdapter(storageRoot);
        }
    }

    @DynamicPropertySource
    static void aiUrl(DynamicPropertyRegistry registry) throws IOException {
        if (!LIVE) {
            fakeAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fakeAi.createContext("/v1/speech/pronunciation-assessment", exchange -> {
                var headers = exchange.getRequestHeaders();
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                boolean valid = "assessment-it-token".equals(headers.getFirst("X-Lyreo-Internal-Token"))
                    && "test-azure-key".equals(headers.getFirst("X-Lyreo-Provider-Credential"))
                    && headers.getFirst("Content-Type").startsWith("multipart/form-data")
                    && body.contains("name=\"referenceText\"") && body.contains("Please open your window")
                    && body.contains("name=\"audio\"") && body.contains("RIFF");
                byte[] response = """
                    {"schemaVersion":1,"outcome":"SCORED","scores":{"overall":90},"words":[],"durationMs":13500}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 422, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            fakeAi.start();
        }
        registry.add("lyreo.ai-service.base-url", () -> LIVE ? SMOKE_URL : "http://127.0.0.1:" + fakeAi.getAddress().getPort());
    }

    @AfterAll static void close() throws IOException {
        if (fakeAi != null) fakeAi.stop(0);
        if (storageRoot != null) {
            try (var paths = Files.walk(storageRoot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Autowired AdminPronunciationAttempts attempts;
    @Autowired AiAdminService ai;
    @Autowired BackgroundJobWorker worker;
    @Autowired ShadowingReference reference;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectStoragePort storage;

    @Test
    void submitQueuesExactlyOnceAndResultIsOwnerScopedRevisionPinnedAndExpiring() throws Exception {
        String key = LIVE ? System.getenv("LYREO_ASSESSMENT_SMOKE_KEY") : "test-azure-key";
        assertThat(key != null && !key.isBlank()).isTrue();
        ai.saveProvider("AZURE_SPEECH", "Azure Speech", "", key, true);
        ai.saveRoute("PRONUNCIATION_ASSESSMENT", "AZURE_SPEECH", "pronunciation-assessment", 0, false, true);
        assertThat(key.equals(jdbc.queryForObject(
            "SELECT encrypted_api_key FROM ai_provider WHERE code='AZURE_SPEECH'", String.class))).isFalse();
        UUID lesson = UUID.randomUUID(), sentence = UUID.randomUUID(), request = UUID.randomUUID();
        String text = "Please open your window";
        when(reference.read(eq(lesson), eq(sentence), eq(7L), anyBoolean()))
            .thenReturn(new ShadowingReference.Sentence(7, text, 2_000, 4_000));
        assertThat(attempts.constraints(lesson, sentence, 7).maxDurationSeconds()).isEqualTo(12);
        assertThatThrownBy(() -> attempts.submit("owner", UUID.randomUUID(), lesson, sentence, 7, wav(216_001)))
            .isInstanceOf(RequestValidationException.class);
        byte[] audio = LIVE ? Files.readAllBytes(Path.of(System.getenv("LYREO_ASSESSMENT_SMOKE_WAV"))) : wav(216_000);
        Callable<AdminPronunciationAttempts.Accepted> submit = () -> attempts.submit("owner", request, lesson, sentence, 7, audio);
        AdminPronunciationAttempts.Accepted accepted;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(submit);
            var second = pool.submit(submit);
            accepted = first.get();
            assertThat(second.get()).isEqualTo(accepted);
        }
        when(reference.read(eq(lesson), eq(sentence), eq(7L), anyBoolean()))
            .thenThrow(new StateConflictException("Draft changed"));
        assertThat(submit.call()).isEqualTo(accepted);
        assertThatThrownBy(() -> attempts.submit("owner", request, lesson, sentence, 7, wav(100)))
            .isInstanceOf(StateConflictException.class);
        assertThatThrownBy(() -> attempts.view("other-admin", accepted.attemptId()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> attempts.recording("other-admin", accepted.attemptId()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> attempts.cancel("other-admin", accepted.attemptId()))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(worker.runOnce()).isEqualTo(1);
        var result = attempts.view("owner", accepted.attemptId());
        assertThat(result.status()).as("assessment status (error %s)", result.errorCode()).isEqualTo(BackgroundJobStatus.SUCCEEDED);
        assertThat(result.revision()).isEqualTo(7);
        assertThat(result.referenceText()).isEqualTo(text);
        assertThat(result.result().path("outcome").asText()).isEqualTo("SCORED");
        if (LIVE) assertThat(result.result().path("transcript").asText().toLowerCase()).contains("window");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_invocation WHERE capability='PRONUNCIATION_ASSESSMENT'", Integer.class))
            .isEqualTo(1);
        if (!LIVE) assertThat(attempts.recording("owner", accepted.attemptId())).isEqualTo(audio);

        jdbc.update("UPDATE admin_pronunciation_attempt SET expires_at=now() WHERE id=?", accepted.attemptId());
        assertThatThrownBy(() -> attempts.view("owner", accepted.attemptId())).isInstanceOf(ResourceNotFoundException.class);
        attempts.purgeExpired();
        assertThat(storage.exists("admin-pronunciation-attempts/" + accepted.attemptId() + "/recording.wav")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_pronunciation_attempt", Integer.class)).isZero();
    }

    private static byte[] wav(int frames) {
        var bytes = ByteBuffer.allocate(44 + 2 * frames).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46464952).putInt(36 + frames * 2).putInt(0x45564157)
            .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16)
            .putInt(0x61746164).putInt(frames * 2);
        while (bytes.hasRemaining()) bytes.putShort((short) 1024);
        return bytes.array();
    }
}
