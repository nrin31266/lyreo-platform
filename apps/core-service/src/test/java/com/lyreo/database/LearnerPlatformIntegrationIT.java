package com.lyreo.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.LyreoApplication;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-stack integration test booting {@link LyreoApplication} against PostgreSQL 18.6,
 * seeding deterministic demo fixtures twice, and verifying Admin + Plus/Free/New Learner flows.
 */
@SpringBootTest(
    classes = LyreoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "spring.profiles.active=dev",
        "spring.scheduling.enabled=false",
        "springdoc.api-docs.enabled=true",
        "lyreo.storage.mode=local",
        "lyreo.security.master-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "lyreo.ai-service.internal-token=integration-test-token",
        "lyreo.ai-service.dev-mock-routes-enabled=true",
        "lyreo.ai-service.confirmed-free-providers=DEV_MOCK"
    }
)
class LearnerPlatformIntegrationIT {
    private static final String WAV_SHA = "2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd";
    private static final String PNG_SHA = "a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f";

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @TempDir
    static Path storageRoot;

    static HttpServer fakeAi;

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) throws IOException {
        fakeAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAi.createContext("/v1/llm/generate", exchange -> {
            byte[] response = """
                {"output":{"text":"Use 'would have' for third conditional."},"usage":{"input_tokens":12,"output_tokens":18},"metadata":{"finish_reason":"stop"}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        fakeAi.createContext("/v1/speech/pronunciation-assessment", exchange -> {
            byte[] response = """
                {"schemaVersion":1,"outcome":"NO_SPEECH","words":[],"durationMs":1000}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        fakeAi.start();
        registry.add("lyreo.storage.local-root", () -> storageRoot.toAbsolutePath().toString());
        registry.add("lyreo.ai-service.base-url", () -> "http://127.0.0.1:" + fakeAi.getAddress().getPort());
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired WebApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EntitlementService entitlements;

    MockMvc mvc;

    static final RequestPostProcessor PLUS_LEARNER =
        auth("offline-demo-minh-anh", "minh-anh@example.test", "LEARNER");
    static final RequestPostProcessor FREE_LEARNER =
        auth("offline-demo-gia-huy", "gia-huy@example.test", "LEARNER");
    static final RequestPostProcessor NEW_LEARNER =
        auth("offline-demo-new", "learner-moi@example.test", "LEARNER");
    static final RequestPostProcessor ADMIN =
        auth("platform-admin-subject", "admin@example.test", "ADMIN");

    @BeforeAll
    static void writeDemoMediaBlobs() throws Exception {
        byte[] wav = silentWavBytes();
        byte[] png = samplePngBytes();
        assertThat(sha256(wav)).isEqualTo(WAV_SHA);
        assertThat(sha256(png)).isEqualTo(PNG_SHA);
        writeBlob(WAV_SHA, wav);
        writeBlob(PNG_SHA, png);

        Path assetsDir = Path.of("../../infra/postgres/seed/assets");
        if (Files.isDirectory(assetsDir)) {
            try (var stream = Files.list(assetsDir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (name.endsWith(".webp") || name.endsWith(".wav") || name.endsWith(".png") || name.endsWith(".jpg")) {
                        byte[] payload = Files.readAllBytes(file);
                        writeBlob(sha256(payload), payload);
                    }
                }
            }
        }
    }

    @AfterAll
    static void stopFakeAi() {
        if (fakeAi != null) {
            fakeAi.stop(0);
        }
    }

    @BeforeEach
    void setupMvcAndSeedTwice() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var demoSeed = new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("seed/it-demo.sql"));
        demoSeed.execute(dataSource);
        demoSeed.execute(dataSource);
    }

    @Test
    void studyBreakdownUsesSubmittedSessionsAndDoesNotTreatDrillAccuracyAsAScaledScore() throws Exception {
        UUID learner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learner, "practice-" + learner);
        jdbc.update("""
            INSERT INTO assessment_session(id, learner_id, domain, mode, snapshot, result, submitted_at)
            VALUES
                (gen_random_uuid(), ?, 'grammar', 'TOPIC', '{}', '{"correct":3,"total":4}', now()),
                (gen_random_uuid(), ?, 'grammar', 'TOPIC', '{}', NULL, NULL),
                (gen_random_uuid(), ?, 'toeic', 'DRILL', '{}', '{"correct":8,"total":10}', now())
            """, learner, learner, learner);

        mvc.perform(get("/api/v1/admin/analytics/learners/" + learner + "/study-profile").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.breakdown.grammarAttempts").value(4))
            .andExpect(jsonPath("$.breakdown.grammarAccuracyPct").value(75))
            .andExpect(jsonPath("$.breakdown.toeicAttempts").value(1))
            .andExpect(jsonPath("$.breakdown.bestToeicScore").value(nullValue()));
    }

    @Test
    void publishesAnnouncementsOnlyForTheCurrentAudienceAndTimeWindow() throws Exception {
        String all = createAnnouncement("For everyone", "ALL", "PUBLISHED", null, null);
        createAnnouncement("For Free", "FREE", "PUBLISHED", null, null);
        createAnnouncement("For Plus", "PLUS", "PUBLISHED", null, null);
        createAnnouncement("Draft only", "ALL", "DRAFT", null, null);
        createAnnouncement("Scheduled", "ALL", "PUBLISHED", "2100-01-01T00:00:00Z", null);
        createAnnouncement("Expired", "ALL", "PUBLISHED", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z");

        var published = json.readTree(all);
        mvc.perform(put("/api/v1/admin/notifications/{id}", published.path("id").asText()).with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Updated for everyone\",\"body\":\"Details\",\"status\":\"PUBLISHED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publishedAt").value(published.path("publishedAt").asText()));

        MvcResult feedBefore = mvc.perform(get("/api/v1/notifications").with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title").value(hasItems("Updated for everyone", "For Free")))
            .andExpect(jsonPath("$.items[?(@.title=='For Plus' || @.title=='Draft only' || @.title=='Scheduled' || @.title=='Expired')]").isEmpty())
            .andExpect(jsonPath("$.items[*].createdBy").isEmpty())
            .andReturn();
        int initialUnread = json.readTree(feedBefore.getResponse().getContentAsString()).path("unreadCount").asInt();
        assertThat(initialUnread).isGreaterThanOrEqualTo(2);

        String notifId = published.path("id").asText();
        mvc.perform(post("/api/v1/notifications/{id}/read", notifId).with(FREE_LEARNER))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unreadCount").value(initialUnread - 1))
            .andExpect(jsonPath("$.items[*].title").value(not(hasItems("Updated for everyone"))));
        mvc.perform(post("/api/v1/notifications/read-all").with(FREE_LEARNER))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications").with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unreadCount").value(0));

        mvc.perform(get("/api/v1/notifications").with(PLUS_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title").value(hasItems("Updated for everyone", "For Plus")))
            .andExpect(jsonPath("$.items[*].title").value(not(hasItems("For Free"))));
        mvc.perform(get("/api/v1/notifications").with(ADMIN)).andExpect(status().isForbidden());
    }

    private String createAnnouncement(String title, String audience, String status, String publishedAt, String expiresAt) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        body.put("body", "Details");
        body.put("targetAudience", audience);
        body.put("status", status);
        body.put("publishedAt", publishedAt);
        body.put("expiresAt", expiresAt);
        return mvc.perform(post("/api/v1/admin/notifications").with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void requiresAnExplicitAudienceForPublicationAndLocksLegacyRecipients() throws Exception {
        String url = "/api/v1/admin/notifications";
        String content = "{\"title\":\"Review draft\",\"body\":\"Review feedback\"}";
        var draft = mvc.perform(post(url).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(content))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
            .andReturn();
        var draftId = json.readTree(draft.getResponse().getContentAsString()).path("id").asText();
        String publication = "{\"title\":\"Review draft\",\"body\":\"Review feedback\",\"status\":\"PUBLISHED\"}";
        mvc.perform(post(url).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(publication))
            .andExpect(status().isBadRequest());
        mvc.perform(put(url + "/{id}", draftId).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(publication))
            .andExpect(status().isBadRequest());

        UUID legacyId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO learner_notification(id,title,body,target_audience,status,created_by)
            VALUES (?,'Legacy private','Feedback','FREE','PUBLISHED','fixture')
            """, legacyId);
        mvc.perform(put(url + "/{id}", legacyId).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Legacy private\",\"body\":\"Feedback\",\"targetAudience\":\"ALL\"}"))
            .andExpect(status().isConflict());
        mvc.perform(put(url + "/{id}", legacyId).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Legacy revised\",\"body\":\"Feedback\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    }

    @Test
    void recordsScheduledPublicationWithItsExpectedTime() throws Exception {
        String planned = "2100-11-27T18:00:00Z";
        createAnnouncement("Review scheduled notification", "ALL", "PUBLISHED", planned, null);
        mvc.perform(get("/api/v1/admin/notifications/activity").param("category", "NOTIFICATION").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.detail=='Review scheduled notification')].action").value(hasItems("NOTIFICATION_SCHEDULED")))
            .andExpect(jsonPath("$.items[?(@.detail=='Review scheduled notification')].scheduledFor").value(hasItems(planned)));
    }

    @Test
    void deliversSelectedNotificationsPrivatelyAndLogsOnlyAdminActivity() throws Exception {
        UUID free = jdbc.queryForObject("SELECT id FROM app_user WHERE keycloak_subject = 'offline-demo-gia-huy'", UUID.class);
        UUID plus = jdbc.queryForObject("SELECT id FROM app_user WHERE keycloak_subject = 'offline-demo-minh-anh'", UUID.class);
        String message = "Private review reminder";
        var adminUrl = "/api/v1/admin/notifications";
        String draft = json.writeValueAsString(Map.of("title", message, "body", "Review your recent practice",
            "category", "STUDY_REMINDER", "targetAudience", "SELECTED", "recipientIds", List.of(free), "status", "DRAFT"));
        String id = json.readTree(mvc.perform(post(adminUrl).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(draft))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.recipientIds[0]").value(free.toString()))
            .andReturn().getResponse().getContentAsString()).path("id").asText();

        mvc.perform(get("/api/v1/notifications").with(FREE_LEARNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title").value(not(hasItems(message))));
        mvc.perform(put(adminUrl + "/{id}", id).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(draft.replace("DRAFT", "PUBLISHED")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications").with(FREE_LEARNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title").value(hasItems(message)))
            .andExpect(jsonPath("$.items[*].recipientIds").isEmpty());
        mvc.perform(get("/api/v1/notifications").with(PLUS_LEARNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title").value(not(hasItems(message))));

        String group = "Review group reminder";
        mvc.perform(post(adminUrl).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("title", group, "body", "Review your recent practice", "category", "STUDY_REMINDER",
                    "targetAudience", "SELECTED", "recipientIds", List.of(free, plus), "status", "PUBLISHED"))))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/notifications").with(FREE_LEARNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title").value(hasItems(group)));
        mvc.perform(get("/api/v1/notifications").with(PLUS_LEARNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].title").value(hasItems(group)));
        mvc.perform(get("/api/v1/notifications").with(NEW_LEARNER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.title=='Private review reminder' || @.title=='Review group reminder')]").isEmpty());

        mvc.perform(put(adminUrl + "/{id}", id).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(draft.replace("DRAFT", "PUBLISHED").replace(free.toString(), plus.toString())))
            .andExpect(status().isConflict());
        mvc.perform(post(adminUrl).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content(draft.replace(free.toString(), UUID.randomUUID().toString())))
            .andExpect(status().isBadRequest());
        mvc.perform(post(adminUrl).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"Broad reminder","body":"Practice","category":"STUDY_REMINDER","status":"PUBLISHED","targetAudience":"ALL"}
                    """))
            .andExpect(status().isBadRequest());
        mvc.perform(delete(adminUrl + "/{id}", id).with(ADMIN)).andExpect(status().isNoContent());
        mvc.perform(get(adminUrl + "/activity").param("category", "NOTIFICATION").with(ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].action")
                .value(hasItems("NOTIFICATION_CREATED", "NOTIFICATION_PUBLISHED", "NOTIFICATION_DELETED")));
        mvc.perform(get(adminUrl + "/activity").param("category", "MEMBERSHIP").with(ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].action").value(hasItems("PLUS_STARTED")));
        mvc.perform(get(adminUrl + "/activity").with(FREE_LEARNER)).andExpect(status().isForbidden());
    }

    @Test
    void exercisesFullAdminAndLearnerJourneysAcrossAllModules() throws Exception {
        // 1. Seed idempotency + role boundary + Analytics honest empty state
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vocabulary_card", Integer.class)).isEqualTo(41);

        mvc.perform(get("/api/v1/admin/learners").with(FREE_LEARNER))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/analytics/overview").with(NEW_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasHistory").value(false));

        String onboardingBody = """
            {"goal":"WORK","currentLevel":"A2","dailyMinutes":15}
            """;
        mvc.perform(put("/api/v1/learner/onboarding").with(NEW_LEARNER)
                .contentType(MediaType.APPLICATION_JSON).content(onboardingBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goal").value("WORK"));
        mvc.perform(put("/api/v1/learner/onboarding").with(NEW_LEARNER)
                .contentType(MediaType.APPLICATION_JSON).content(onboardingBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dailyMinutes").value(15));

        // 2. Entitlement matrix + Plus pack gate + Admin grant & revoke + concurrency quota race
        mvc.perform(get("/api/v1/learner/entitlements").with(PLUS_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entitlements[?(@.featureKey=='vocabulary.plus-packs')].granted", contains(true)));

        mvc.perform(get("/api/v1/learner/entitlements").with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entitlements[?(@.featureKey=='toeic.full-access')].granted", contains(false)));

        String plusPackId = "50000000-0000-0000-0000-000000000212";
        mvc.perform(get("/api/v1/vocabulary/packs/" + plusPackId + "/units").with(FREE_LEARNER))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FEATURE_ENTITLEMENT_REQUIRED"))
            .andExpect(jsonPath("$.featureKey").value("vocabulary.plus-packs"));

        // Plus learner streams gated entry audio
        mvc.perform(get("/api/v1/vocabulary/entries/50000000-0000-0000-0000-000000000239/media/50000000-0000-0000-0000-000000000101")
                .with(PLUS_LEARNER))
            .andExpect(status().isOk());

        UUID freeLearnerId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        String planUrl = "/api/v1/admin/entitlements/learners/" + freeLearnerId + "/plan";
        mvc.perform(get(planUrl).with(FREE_LEARNER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/entitlements/learners/" + UUID.randomUUID() + "/plan").with(ADMIN))
            .andExpect(status().isNotFound());
        mvc.perform(put(planUrl).with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"tier":"PLUS","durationDays":3}
                    """))
            .andExpect(status().isBadRequest());
        mvc.perform(put(planUrl).with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"tier":"PLUS","durationDays":30}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tier").value("PLUS"))
            .andExpect(jsonPath("$.history[0].tier").value("PLUS"))
            .andExpect(jsonPath("$.history.length()").value(3));

        mvc.perform(get("/api/v1/vocabulary/packs/" + plusPackId + "/units").with(FREE_LEARNER))
            .andExpect(status().isOk());

        mvc.perform(put(planUrl).with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"tier":"FREE"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tier").value("FREE"))
            .andExpect(jsonPath("$.history[0].tier").value("FREE"))
            .andExpect(jsonPath("$.history[1].tier").value("PLUS"))
            .andExpect(jsonPath("$.history.length()").value(4));

        mvc.perform(get("/api/v1/vocabulary/packs/" + plusPackId + "/units").with(FREE_LEARNER))
            .andExpect(status().isForbidden());

        // Concurrent quota race on chat.tutor (set free remaining=1 -> 4 already used out of 5? Actually FREE max=10 -> consume 9 first)
        for (int i = 0; i < 9; i++) {
            entitlements.consumeQuota(freeLearnerId, FeatureKey.of("chat.tutor"), "warmup-" + i);
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var call1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return mvc.perform(post("/api/v1/chat/ask").with(FREE_LEARNER)
                        .header("Idempotency-Key", "race-op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"learnerLevel":"B1","question":"Explain present simple"}
                            """))
                    .andReturn().getResponse().getStatus();
            });
            var call2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return mvc.perform(post("/api/v1/chat/ask").with(FREE_LEARNER)
                        .header("Idempotency-Key", "race-op-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"learnerLevel":"B1","question":"Explain past simple"}
                            """))
                    .andReturn().getResponse().getStatus();
            });
            ready.await();
            start.countDown();
            List<Integer> statuses = List.of(call1.get(), call2.get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 403);
        }

        // 3. Vocabulary 6-mode study session (revision conflict + idempotent submit) & Personal Collections + SRS
        String startKey = UUID.randomUUID().toString();
        MvcResult startVocab = mvc.perform(post("/api/v1/vocabulary/units/50000000-0000-0000-0000-000000000221/sessions")
                .with(PLUS_LEARNER)
                .header("Idempotency-Key", startKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"mode":"CHOOSE_MEANING","limit":3}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode vocabSession = json.readTree(startVocab.getResponse().getContentAsString());
        String vocabSessionId = vocabSession.path("id").asText();
        long vocabRevision = vocabSession.path("revision").asLong();

        // Build valid answers for CHOOSE_MEANING
        Map<String, String> vocabAnswers = new LinkedHashMap<>();
        for (JsonNode q : vocabSession.path("questions")) {
            vocabAnswers.put(q.path("id").asText(), q.path("options").get(0).path("key").asText());
        }
        String saveAnswersJson = json.writeValueAsString(Map.of("revision", 999L, "answers", vocabAnswers));
        mvc.perform(put("/api/v1/vocabulary/sessions/" + vocabSessionId + "/answers")
                .with(PLUS_LEARNER).contentType(MediaType.APPLICATION_JSON).content(saveAnswersJson))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_REVISION"));

        MvcResult savedVocab = mvc.perform(put("/api/v1/vocabulary/sessions/" + vocabSessionId + "/answers")
                .with(PLUS_LEARNER).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", vocabRevision, "answers", vocabAnswers))))
            .andExpect(status().isOk())
            .andReturn();
        long nextVocabRev = json.readTree(savedVocab.getResponse().getContentAsString()).path("revision").asLong();

        String submitVocabKey = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/vocabulary/sessions/" + vocabSessionId + "/submit")
                .with(PLUS_LEARNER).header("Idempotency-Key", submitVocabKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", nextVocabRev))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // Save two distinct senses of "bank" & manage custom collection without deleting orphaned cards
        MvcResult colRes = mvc.perform(post("/api/v1/vocabulary/collections").with(FREE_LEARNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Senses of Bank","coverPreset":"BLUE_BOOK"}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        String customColId = json.readTree(colRes.getResponse().getContentAsString()).path("id").asText();

        mvc.perform(post("/api/v1/vocabulary/saved-items").with(FREE_LEARNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"term":"bank","partOfSpeech":"n","meaningVi":"ngân hàng","definitionEn":"financial institution","sourceContextType":"VOCABULARY_ENTRY","sourceEntryId":"50000000-0000-0000-0000-000000000237","senseIndex":0,"collectionIds":["%s"]}
                    """.formatted(customColId)))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/vocabulary/saved-items").with(FREE_LEARNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"term":"bank","partOfSpeech":"n","meaningVi":"bờ sông","definitionEn":"river bank","sourceContextType":"VOCABULARY_ENTRY","sourceEntryId":"50000000-0000-0000-0000-000000000237","senseIndex":1,"collectionIds":["%s"]}
                    """.formatted(customColId)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/vocabulary/collections/" + customColId + "/items").with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2));

        mvc.perform(delete("/api/v1/vocabulary/collections/" + customColId).with(FREE_LEARNER))
            .andExpect(status().isNoContent());

        // Cross-learner read on PLUS learner's collection returns 404
        mvc.perform(get("/api/v1/vocabulary/collections/50000000-0000-0000-0000-000000000252/items").with(FREE_LEARNER))
            .andExpect(status().isNotFound());

        // SRS Review idempotency + payload mismatch conflict
        String reviewKey = UUID.randomUUID().toString();
        String reviewCardId = "50000000-0000-0004-0000-00000000000b";
        mvc.perform(post("/api/v1/vocabulary/cards/" + reviewCardId + "/review").with(PLUS_LEARNER)
                .header("Idempotency-Key", reviewKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"rating":"GOOD","expectedReviewCount":0}
                    """))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/vocabulary/cards/" + reviewCardId + "/review").with(PLUS_LEARNER)
                .header("Idempotency-Key", reviewKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"rating":"AGAIN","expectedReviewCount":0}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 4. Lesson published catalog + dictation masking + completion + shadowing constraints & silent submission
        mvc.perform(get("/api/v1/lessons").with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.id=='50000000-0000-0000-0000-000000000402')]").isEmpty());

        mvc.perform(get("/api/v1/lessons/50000000-0000-0000-0000-000000000401/activities/50000000-0000-0000-0000-000000000412")
                .with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sentences[0].expectedText").value(nullValue()));

        mvc.perform(post("/api/v1/lessons/50000000-0000-0000-0000-000000000401/activities/50000000-0000-0000-0000-000000000412/dictation/attempts")
                .with(FREE_LEARNER).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sentenceId":"50000000-0000-0000-0000-000000000421","answer":"Welcome to Lyreo."}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serverScore").value(100));

        mvc.perform(post("/api/v1/lessons/50000000-0000-0000-0000-000000000401/activities/50000000-0000-0000-0000-000000000412/dictation/attempts")
                .with(FREE_LEARNER).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sentenceId":"50000000-0000-0000-0000-000000000422","answer":"Let us practice listening every day."}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activityCompleted").value(true));

        mvc.perform(get("/api/v1/lessons/50000000-0000-0000-0000-000000000401/activities/50000000-0000-0000-0000-000000000413/shadowing/sentences/50000000-0000-0000-0000-000000000421/constraints")
                .param("revision", "1").with(PLUS_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxDurationSeconds").isNumber());

        var silentFile = new MockMultipartFile("audio", "silent.wav", "audio/wav", silentWavBytes());
        mvc.perform(multipart("/api/v1/lessons/50000000-0000-0000-0000-000000000401/activities/50000000-0000-0000-0000-000000000413/shadowing/sentences/50000000-0000-0000-0000-000000000421/attempts")
                .file(silentFile)
                .param("revision", "1")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(PLUS_LEARNER))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.attemptId").isNotEmpty());

        // 5. TOEIC Part drill + session discovery + raw score null scaled fields
        MvcResult toeicStart = mvc.perform(post("/api/v1/toeic/catalog/60000000-0000-0000-0000-000000000201/sessions")
                .with(PLUS_LEARNER).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"mode":"DRILL","parts":[5]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.questions.length()").value(30))
            .andReturn();
        JsonNode toeicSession = json.readTree(toeicStart.getResponse().getContentAsString());
        String toeicSessionId = toeicSession.path("id").asText();
        long toeicRevision = toeicSession.path("revision").asLong();

        mvc.perform(get("/api/v1/assessment-sessions").param("domain", "toeic").param("status", "OPEN")
                .with(PLUS_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.id=='" + toeicSessionId + "')]").isNotEmpty());

        mvc.perform(post("/api/v1/assessment-sessions/" + toeicSessionId + "/submit").with(PLUS_LEARNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", toeicRevision))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.listeningScaledScore").value(nullValue()))
            .andExpect(jsonPath("$.result.readingScaledScore").value(nullValue()));

        // 6. Admin Curriculum CRUD + Admin Gamification read & adjustment
        MvcResult adminPathRes = mvc.perform(post("/api/v1/admin/curriculum/paths").with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"SECOND_PATH","name":"Secondary Listening","level":"B1","description":"Shares lesson 401","status":"PUBLISHED","sections":[{"title":"Step 1","position":1,"items":[{"position":1,"contentType":"LESSON","contentReferenceId":"50000000-0000-0000-0000-000000000401","required":true,"unlockRule":"NONE"}]}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andReturn();
        String secondPathId = json.readTree(adminPathRes.getResponse().getContentAsString()).path("id").asText();

        // Free learner (not enrolled in SECOND_PATH) completed lesson 401 above; enrollment check still shows NOT_ENROLLED / 0 completed
        mvc.perform(get("/api/v1/curriculum/paths/" + secondPathId).with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enrollmentStatus").value(nullValue()));

        MvcResult beforeGamRes = mvc.perform(get("/api/v1/admin/gamification/learners/30000000-0000-0000-0000-000000000001").with(ADMIN))
                .andExpect(status().isOk())
                .andReturn();
        int beforeDiamonds = json.readTree(beforeGamRes.getResponse().getContentAsString()).path("diamondBalance").asInt();

        mvc.perform(post("/api/v1/admin/gamification/learners/30000000-0000-0000-0000-000000000001/diamonds").with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":25,"reason":"E2E QA bonus","idempotencyKey":"e2e-admin-bonus-1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(beforeDiamonds + 25));

        mvc.perform(get("/api/v1/admin/gamification/learners/30000000-0000-0000-0000-000000000001").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diamondBalance").value(beforeDiamonds + 25))
            .andExpect(jsonPath("$.transactions[0].reason").value("E2E QA bonus"));

        mvc.perform(get("/api/v1/analytics/overview").with(FREE_LEARNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasHistory").value(true));

        // 7. Admin Gamification, Entitlements, Analytics, Chat Telemetry & Content Media
        mvc.perform(get("/api/v1/admin/gamification/missions").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='DAILY_SHADOWING_3')]").isNotEmpty());
        mvc.perform(get("/api/v1/admin/gamification/achievements").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='TEN_LESSONS')]").isNotEmpty());
        mvc.perform(get("/api/v1/admin/gamification/reward-policy").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ruleVersion").isNotEmpty());
        mvc.perform(put("/api/v1/admin/gamification/reward-policy").with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"directLessonActivityDiamondsEnabled":true,"xpPerLevel":120,"xpGrowthPerLevel":20,"maxLevel":99,"xpLevelMultiplier":1.03,"milestoneInterval":5,"milestoneDiamondBase":15,"milestoneDiamondGrowth":10}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.xpPerLevel").value(120))
            .andExpect(jsonPath("$.ruleVersion").isNotEmpty());
        mvc.perform(get("/api/v1/admin/notifications/activity").param("category", "REWARD").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[*].action").value(hasItems("REWARD_POLICY_UPDATED")));

        // ADMIN cannot access learner-only routes, while /api/v1/lexicon/** allows both ADMIN and LEARNER
        mvc.perform(get("/api/v1/lessons").with(ADMIN)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/gamification/summary").with(ADMIN)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/lexicon/search").param("q", "work").with(ADMIN)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/entitlements/features").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(8));
        mvc.perform(get("/api/v1/admin/entitlements/quotas").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.featureKey=='chat.tutor')]").isNotEmpty());
        mvc.perform(get("/api/v1/admin/entitlements/audit").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/admin/analytics/summary").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeLearners7d").isNumber());
        mvc.perform(get("/api/v1/admin/analytics/dashboard").param("days", "14").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.period.days").value(14))
            .andExpect(jsonPath("$.dailySeries.length()").value(14))
            .andExpect(jsonPath("$.kpis.totalLearners").isNumber())
            .andExpect(jsonPath("$.kpis.activePlusLearners").isNumber())
            .andExpect(jsonPath("$.contentInventory.curriculumPathsPublished").isNumber())
            .andExpect(jsonPath("$.topCurriculumPaths").isArray());
        mvc.perform(get("/api/v1/admin/analytics/dashboard").with(FREE_LEARNER))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/chat/summary").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalConversations").isNumber());
        mvc.perform(get("/api/v1/admin/content-media").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.filename=='vocab-coffee-ordering.webp')]").isNotEmpty());
    }

    private static RequestPostProcessor auth(String subject, String email, String role) {
        return jwt()
            .jwt(token -> token.subject(subject).claim("email", email))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static void writeBlob(String sha, byte[] payload) throws IOException {
        Path target = storageRoot.resolve("media").resolve("sha256").resolve(sha.substring(0, 2)).resolve(sha);
        Files.createDirectories(target.getParent());
        Files.write(target, payload);
    }

    private static byte[] silentWavBytes() {
        byte[] samples = new byte[3200];
        ByteBuffer buf = ByteBuffer.allocate(44 + samples.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'R', 'I', 'F', 'F'});
        buf.putInt(36 + samples.length);
        buf.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(16000);
        buf.putInt(32000);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put(new byte[]{'d', 'a', 't', 'a'});
        buf.putInt(samples.length);
        buf.put(samples);
        return buf.array();
    }

    private static byte[] samplePngBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(16);
        ihdr.putInt(16);
        ihdr.put((byte) 8);
        ihdr.put((byte) 2);
        ihdr.put((byte) 0);
        ihdr.put((byte) 0);
        ihdr.put((byte) 0);
        writePngChunk(out, new byte[]{'I', 'H', 'D', 'R'}, ihdr.array());

        byte[] row = new byte[1 + 16 * 3];
        row[0] = 0;
        for (int x = 0; x < 16; x++) {
            row[1 + x * 3] = 0x0f;
            row[1 + x * 3 + 1] = 0x76;
            row[1 + x * 3 + 2] = 0x6e;
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < 16; y++) {
            raw.write(row);
        }
        var deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] tmp = new byte[256];
        while (!deflater.finished()) {
            int n = deflater.deflate(tmp);
            compressed.write(tmp, 0, n);
        }
        deflater.end();
        writePngChunk(out, new byte[]{'I', 'D', 'A', 'T'}, compressed.toByteArray());
        writePngChunk(out, new byte[]{'I', 'E', 'N', 'D'}, new byte[0]);
        return out.toByteArray();
    }

    private static void writePngChunk(ByteArrayOutputStream out, byte[] kind, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(kind);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(kind);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
