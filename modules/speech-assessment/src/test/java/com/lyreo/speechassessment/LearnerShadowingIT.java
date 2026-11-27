package com.lyreo.speechassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.ai.application.admin.AiAdminService;
import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.entitlement.api.FeatureEntitlementRequiredException;
import com.lyreo.entitlement.api.QuotaExceededException;
import com.lyreo.lesson.domain.authoring.AuthoringOptions;
import com.lyreo.lesson.domain.authoring.AuthoringProfile;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import com.lyreo.platform.jobs.infrastructure.BackgroundJobWorker;
import com.lyreo.platform.storage.LocalFileObjectStorageAdapter;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.speechassessment.application.LearnerPronunciationAttempts;
import com.lyreo.speechassessment.application.RecordingLimits;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LearnerShadowingIT.Config.class, properties = {
    "lyreo.ai-service.internal-token=shadowing-it-token",
    "lyreo.ai-service.confirmed-free-providers=AZURE_SPEECH",
    "lyreo.security.master-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class LearnerShadowingIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    static HttpServer fakeAi;
    static Path storageRoot;
    static final AtomicReference<String> NEXT_OUTCOME = new AtomicReference<>("SCORED");
    static final AtomicInteger NEXT_HTTP_STATUS = new AtomicInteger(200);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws IOException {
        Path migrations = Path.of("apps/core-service/src/main/resources/db/migration");
        if (!Files.isDirectory(migrations)) {
            migrations = Path.of("../../apps/core-service/src/main/resources/db/migration");
        }
        String location = "filesystem:" + migrations.toAbsolutePath().normalize();
        registry.add("spring.flyway.locations", () -> location);

        fakeAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAi.createContext("/v1/speech/pronunciation-assessment", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int status = NEXT_HTTP_STATUS.get();
            String outcome = NEXT_OUTCOME.get();
            byte[] response;
            if (status != 200) {
                response = "{\"error\":\"provider failure\"}".getBytes(StandardCharsets.UTF_8);
            } else if ("NO_SPEECH".equals(outcome)) {
                response = """
                    {"schemaVersion":1,"outcome":"NO_SPEECH","scores":{},"words":[],"transcript":""}
                    """.getBytes(StandardCharsets.UTF_8);
            } else {
                response = """
                    {"schemaVersion":1,"outcome":"SCORED","scores":{"overall":92,"accuracy":94,"fluency":90},"words":[],"transcript":"Please open your window"}
                    """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        fakeAi.start();
        registry.add("lyreo.ai-service.base-url", () -> "http://127.0.0.1:" + fakeAi.getAddress().getPort());
    }

    @BeforeAll
    static void initRouteOnce() {
        // Initialized per test via setupAiRoute
    }

    @AfterAll
    static void shutdown() throws IOException {
        if (fakeAi != null) fakeAi.stop(0);
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (var paths = Files.walk(storageRoot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @org.springframework.boot.autoconfigure.AutoConfigurationPackage(basePackages = "com.lyreo.identity")
    @EnableConfigurationProperties(RecordingLimits.class)
    @ComponentScan(basePackages = {
        "com.lyreo.ai",
        "com.lyreo.entitlement",
        "com.lyreo.lesson",
        "com.lyreo.identity",
        "com.lyreo.platform.config",
        "com.lyreo.platform.jobs",
        "com.lyreo.speechassessment"
    })
    static class Config {
        @Bean
        ObjectStoragePort storage() throws IOException {
            storageRoot = Files.createTempDirectory("shadowing-it-");
            return new LocalFileObjectStorageAdapter(storageRoot);
        }
    }

    @Autowired LearnerPronunciationAttempts attempts;
    @Autowired AiAdminService aiAdmin;
    @Autowired BackgroundJobWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ObjectStoragePort storage;

    private UUID lessonId;
    private UUID shadowingActivityId;
    private UUID sentenceId;
    private UUID learnerId;
    private UUID otherLearnerId;

    @BeforeEach
    void setupFixture() {
        NEXT_OUTCOME.set("SCORED");
        NEXT_HTTP_STATUS.set(200);
        aiAdmin.saveProvider("AZURE_SPEECH", "Azure Speech", "", "test-azure-key", true);
        aiAdmin.saveRoute("PRONUNCIATION_ASSESSMENT", "AZURE_SPEECH", "pronunciation-assessment", 0, false, true);

        learnerId = insertUser();
        otherLearnerId = insertUser();

        lessonId = UUID.randomUUID();
        sentenceId = UUID.randomUUID();
        String audioKey = "lessons/" + lessonId + "/audio.wav";
        storage.put(audioKey, "audio/wav", wav(32_000));

        AuthoringSentence sentence = new AuthoringSentence(sentenceId, "Please open your window.", "LEARN");
        sentence.words = List.of(
            new AuthoringSentence.Word("Please", 0, 500, 0, 6, 0, 1),
            new AuthoringSentence.Word("open", 500, 1000, 7, 11, 1, 2),
            new AuthoringSentence.Word("your", 1000, 1400, 12, 16, 2, 3),
            new AuthoringSentence.Word("window", 1400, 2000, 17, 23, 3, 4)
        );
        sentence.shadowingSelected = true;

        jdbc.update(
            "INSERT INTO lesson(id, title, source_type, canonical_audio_object_key, status) VALUES (?, ?, 'TEXT', ?, 'READY')",
            lessonId, "Shadowing Lesson", audioKey
        );
        jdbc.update(
            "INSERT INTO lesson_authoring(lesson_id, source_start_ms, source_end_ms, revision, document, published_at, generation_options, content_profile) VALUES (?, 0, 4000, 5, CAST(? AS jsonb), NULL, CAST(? AS jsonb), CAST(? AS jsonb))",
            lessonId,
            mapper.writeValueAsString(List.of(sentence)),
            mapper.writeValueAsString(new AuthoringOptions(Set.of("SHADOWING"), Set.of("TRANSLATION"), "vi-VN", "AUTO", 20, 8, "DISABLED", "US")),
            mapper.writeValueAsString(new AuthoringProfile())
        );
        jdbc.update(
            "INSERT INTO lesson_sentence(id, lesson_id, position, text, audio_start_ms, audio_end_ms) VALUES (?, ?, 0, ?, 0, 2000)",
            sentenceId, lessonId, sentence.text
        );
        shadowingActivityId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO lesson_activity(id, lesson_id, activity_type, position, enabled) VALUES (?, ?, 'SHADOWING', 1, TRUE)",
            shadowingActivityId, lessonId
        );
        jdbc.update(
            "INSERT INTO lesson_activity_sentence(activity_id, sentence_id) VALUES (?, ?)",
            shadowingActivityId, sentenceId
        );
        jdbc.update("UPDATE lesson SET status = 'PUBLISHED' WHERE id = ?", lessonId);
        jdbc.update("UPDATE lesson_authoring SET published_at = now() WHERE lesson_id = ?", lessonId);
    }

    @Test
    void shadowingLifecycleHandlesQuotaRevisionIdempotencyOwnershipCompletionNoSpeechCancelErrorAndPurge() throws Exception {
        // 1. Constraints + StaleRevisionException
        var constraints = attempts.constraints(learnerId, lessonId, shadowingActivityId, sentenceId, 5L);
        assertThat(constraints.revision()).isEqualTo(5L);
        assertThat(constraints.quotaAllowed()).isTrue();
        assertThat(constraints.quotaLimit()).isEqualTo(5);
        assertThat(constraints.quotaRemaining()).isEqualTo(5);

        assertThatThrownBy(() -> attempts.constraints(learnerId, lessonId, shadowingActivityId, sentenceId, 4L))
            .isInstanceOf(StaleRevisionException.class);
        assertThatThrownBy(() -> attempts.submit(learnerId, UUID.randomUUID(), lessonId, shadowingActivityId, sentenceId, 4L, wav(16_000)))
            .isInstanceOf(StaleRevisionException.class);

        // 2. Concurrent idempotent submit reserves quota ONCE and creates ONE attempt
        UUID key1 = UUID.randomUUID();
        byte[] audio = wav(16_000);
        Callable<LearnerPronunciationAttempts.Accepted> submitCall = () ->
            attempts.submit(learnerId, key1, lessonId, shadowingActivityId, sentenceId, 5L, audio);
        LearnerPronunciationAttempts.Accepted accepted1;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(submitCall);
            var f2 = pool.submit(submitCall);
            accepted1 = f1.get();
            assertThat(f2.get().attemptId()).isEqualTo(accepted1.attemptId());
        }
        assertThat(attempts.constraints(learnerId, lessonId, shadowingActivityId, sentenceId, 5L).quotaRemaining()).isEqualTo(4);

        // Payload mismatch on same key throws IdempotencyConflictException
        assertThatThrownBy(() -> attempts.submit(learnerId, key1, lessonId, shadowingActivityId, sentenceId, 5L, wav(8_000)))
            .isInstanceOf(IdempotencyConflictException.class);

        // Owner-only isolation (other learner receives 404)
        assertThatThrownBy(() -> attempts.view(otherLearnerId, accepted1.attemptId()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> attempts.recording(otherLearnerId, accepted1.attemptId()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> attempts.cancel(otherLearnerId, accepted1.attemptId()))
            .isInstanceOf(ResourceNotFoundException.class);

        // 3. SCORED execution settles quota and advances shadowing + lesson completion
        assertThat(worker.runOnce()).isEqualTo(1);
        var scoredView = attempts.view(learnerId, accepted1.attemptId());
        assertThat(scoredView.status()).isEqualTo(BackgroundJobStatus.SUCCEEDED);
        assertThat(scoredView.outcome()).isEqualTo("SCORED");
        assertThat(scoredView.overallScore()).isEqualTo(92);
        assertThat(scoredView.audioAvailable()).isTrue();
        assertThat(attempts.recording(learnerId, accepted1.attemptId())).startsWith("RIFF".getBytes(StandardCharsets.US_ASCII));

        assertThat(attempts.constraints(learnerId, lessonId, shadowingActivityId, sentenceId, 5L).quotaRemaining()).isEqualTo(4);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM lesson_activity_progress WHERE learner_id = ? AND activity_id = ?",
            String.class, learnerId, shadowingActivityId
        )).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM lesson_progress WHERE learner_id = ? AND lesson_id = ?",
            String.class, learnerId, lessonId
        )).isEqualTo("COMPLETED");

        // 4. NO_SPEECH releases quota cleanly without advancing completion
        UUID learner2 = insertUser();
        NEXT_OUTCOME.set("NO_SPEECH");
        var noSpeechAccepted = attempts.submit(learner2, UUID.randomUUID(), lessonId, shadowingActivityId, sentenceId, 5L, audio);
        assertThat(attempts.constraints(learner2, lessonId, shadowingActivityId, sentenceId, 5L).quotaRemaining()).isEqualTo(4);
        assertThat(worker.runOnce()).isEqualTo(1);

        var noSpeechView = attempts.view(learner2, noSpeechAccepted.attemptId());
        assertThat(noSpeechView.status()).isEqualTo(BackgroundJobStatus.SUCCEEDED);
        assertThat(noSpeechView.outcome()).isEqualTo("NO_SPEECH");
        assertThat(noSpeechView.overallScore()).isNull();
        // Quota restored!
        assertThat(attempts.constraints(learner2, lessonId, shadowingActivityId, sentenceId, 5L).quotaRemaining()).isEqualTo(5);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM lesson_practice_attempt WHERE learner_id = ?",
            Integer.class, learner2
        )).isZero();

        // 5. Cancel while QUEUED releases quota cleanly
        var cancelledAccepted = attempts.submit(learner2, UUID.randomUUID(), lessonId, shadowingActivityId, sentenceId, 5L, audio);
        assertThat(attempts.constraints(learner2, lessonId, shadowingActivityId, sentenceId, 5L).quotaRemaining()).isEqualTo(4);
        attempts.cancel(learner2, cancelledAccepted.attemptId());
        assertThat(attempts.view(learner2, cancelledAccepted.attemptId()).status()).isEqualTo(BackgroundJobStatus.CANCELLED);
        assertThat(attempts.constraints(learner2, lessonId, shadowingActivityId, sentenceId, 5L).quotaRemaining()).isEqualTo(5);

        // 6. Provider permanent error releases quota cleanly
        NEXT_HTTP_STATUS.set(401);
        var failedAccepted = attempts.submit(learner2, UUID.randomUUID(), lessonId, shadowingActivityId, sentenceId, 5L, audio);
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(attempts.view(learner2, failedAccepted.attemptId()).status()).isEqualTo(BackgroundJobStatus.FAILED);
        assertThat(attempts.constraints(learner2, lessonId, shadowingActivityId, sentenceId, 5L).quotaRemaining()).isEqualTo(5);

        // 7. Quota exhaustion blocks 6th attempt
        NEXT_HTTP_STATUS.set(200);
        NEXT_OUTCOME.set("SCORED");
        for (int i = 0; i < 5; i++) {
            attempts.submit(learner2, UUID.randomUUID(), lessonId, shadowingActivityId, sentenceId, 5L, audio);
        }
        assertThatThrownBy(() -> attempts.submit(learner2, UUID.randomUUID(), lessonId, shadowingActivityId, sentenceId, 5L, audio))
            .isInstanceOf(QuotaExceededException.class);

        // 8. 24h audio purge deletes WAV object and blocks /audio while keeping attempt row
        jdbc.update("UPDATE learner_pronunciation_attempt SET expires_at = now() - interval '1 minute' WHERE id = ?", accepted1.attemptId());
        assertThatThrownBy(() -> attempts.recording(learnerId, accepted1.attemptId()))
            .isInstanceOf(ResourceNotFoundException.class);
        attempts.purgeExpired();
        assertThat(storage.exists(LearnerPronunciationAttempts.objectKey(accepted1.attemptId()))).isFalse();
        var afterPurge = attempts.view(learnerId, accepted1.attemptId());
        assertThat(afterPurge.audioAvailable()).isFalse();
        assertThat(afterPurge.recordingPurgedAt()).isNotNull();
        assertThat(afterPurge.overallScore()).isEqualTo(92);

        // 9. Marking lesson FEATURE (Plus-only) blocks Free learner on constraints, submit, and view
        jdbc.update(
            "UPDATE lesson_metadata SET access_mode = 'FEATURE', required_feature_key = 'lesson.plus-lessons' WHERE lesson_id = ?",
            lessonId
        );
        assertThatThrownBy(() -> attempts.constraints(learner2, lessonId, shadowingActivityId, sentenceId, 5L))
            .isInstanceOf(FeatureEntitlementRequiredException.class);
        assertThatThrownBy(() -> attempts.submit(learner2, UUID.randomUUID(), lessonId, shadowingActivityId, sentenceId, 5L, audio))
            .isInstanceOf(FeatureEntitlementRequiredException.class);
        assertThatThrownBy(() -> attempts.view(learnerId, accepted1.attemptId()))
            .isInstanceOf(FeatureEntitlementRequiredException.class);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", id, "sub-" + id);
        return id;
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
