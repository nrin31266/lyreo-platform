package com.lyreo.gamification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.lesson.ShadowingAssessedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.gamification.application.GamificationAdminService;
import com.lyreo.gamification.application.LessonRewardListener;
import com.lyreo.gamification.application.MissionEventListener;
import com.lyreo.gamification.application.StreakService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GamificationIdempotencyIT.TestConfig.class)
class GamificationIdempotencyIT {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void flywayLocation(DynamicPropertyRegistry registry) {
        Path migrations = Path.of("../../apps/core-service/src/main/resources/db/migration")
            .toAbsolutePath()
            .normalize();
        registry.add("spring.flyway.locations", () -> "filesystem:" + migrations);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableMethodSecurity
    @EnableJpaRepositories(basePackages = "com.lyreo.identity")
    @EntityScan(basePackages = "com.lyreo.identity")
    @ComponentScan(basePackages = {
        "com.lyreo.gamification",
        "com.lyreo.identity",
        "com.lyreo.platform.config"
    })
    static class TestConfig {
        @Bean
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
                )
                .build();
        }

        @RestControllerAdvice
        static class TestErrors {
            @ExceptionHandler(ResourceNotFoundException.class)
            ResponseEntity<java.util.Map<String, String>> notFound(ResourceNotFoundException ex) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("detail", ex.getMessage()));
            }

            @ExceptionHandler({StateConflictException.class, IdempotencyConflictException.class})
            ResponseEntity<java.util.Map<String, String>> conflict(StateConflictException ex) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of("detail", ex.getMessage()));
            }

            @ExceptionHandler(RequestValidationException.class)
            ResponseEntity<java.util.Map<String, String>> badRequest(RequestValidationException ex) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of("detail", ex.getMessage()));
            }
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MissionEventListener missionListener;

    @Autowired
    private LessonRewardListener lessonRewardListener;

    @Autowired
    private StreakService streakService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private MockMvc mvc;

    @BeforeEach
    void setupMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void adminSummaryResetsTheCurrentStreakAfterAMissedDay() throws Exception {
        var learner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, keycloak_subject) VALUES (?, ?)", learner, learner.toString());
        var today = LocalDate.now(ZoneOffset.UTC);
        var admin = auth("streak-admin", "ADMIN");
        var url = "/api/v1/admin/gamification/learners/" + learner;

        for (int daysAgo : new int[]{2, 3}) {
            streakService.recordActivity(UUID.randomUUID(), learner,
                today.minusDays(daysAgo).atTime(12, 0).toInstant(ZoneOffset.UTC));
        }
        mvc.perform(get(url).with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentStreak").value(0))
            .andExpect(jsonPath("$.longestStreak").value(2));

        streakService.recordActivity(UUID.randomUUID(), learner, today.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC));
        mvc.perform(get(url).with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentStreak").value(3));
    }

    @Test
    void deduplicatesPartialMissionProgressAwardsXpAndDiamondsOnceAndEvaluatesStreaksAchievementsAndCutover() throws Exception {
        String subject = "gam-learner-" + UUID.randomUUID();
        var learnerAuth = auth(subject, "LEARNER");
        var adminAuth = auth("gam-admin-" + UUID.randomUUID(), "ADMIN");

        // Provision learner via summary endpoint
        mvc.perform(get("/api/v1/gamification/summary").with(learnerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.level").value(1))
            .andExpect(jsonPath("$.xp").value(0))
            .andExpect(jsonPath("$.diamondBalance").value(0));

        UUID learnerId = jdbc.queryForObject(
            "SELECT id FROM app_user WHERE keycloak_subject = ?",
            UUID.class,
            subject
        );

        // 1. Mission deduplication at 1/5 (DAILY_GRAMMAR_5) in today's active UTC period
        Instant today = LocalDate.now(ZoneOffset.UTC).atTime(10, 0).toInstant(ZoneOffset.UTC);
        Instant twoDaysAgo = today.minusSeconds(2L * 86_400L);
        Instant yesterday = today.minusSeconds(86_400L);
        UUID grammarAttempt1 = UUID.randomUUID();
        UUID grammarItem1 = UUID.randomUUID();
        var grammar1 = new GrammarQuestionAnsweredEvent(
            learnerId, grammarAttempt1, grammarItem1, UUID.randomUUID(), UUID.randomUUID(), 2, true, today
        );
        missionListener.grammar(grammar1);
        // Replay at 1/5 -> must stay 1/5!
        missionListener.grammar(grammar1);

        Integer progressAfterReplay = jdbc.queryForObject(
            """
            SELECT progress FROM mission_progress
            WHERE learner_id = ? AND mission_id = '10000000-0000-0000-0000-000000000004'
            """,
            Integer.class,
            learnerId
        );
        assertThat(progressAfterReplay).isEqualTo(1);

        // Completing an uncompleted mission via manual claim is rejected with 409
        mvc.perform(post("/api/v1/gamification/missions/{id}/claim", "10000000-0000-0000-0000-000000000003")
                .with(learnerAuth))
            .andExpect(status().isConflict());

        // Complete remaining 4 distinct grammar answers
        for (int i = 2; i <= 5; i++) {
            var evt = new GrammarQuestionAnsweredEvent(
                learnerId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, true, today
            );
            missionListener.grammar(evt);
            if (i == 5) {
                // Replay after completion
                missionListener.grammar(evt);
            }
        }

        // Completed mission is NOT auto-claimed; manual claim populates snapshot & awards 10 XP / 2 Diamonds once
        String beforeClaimSnapshot = jdbc.queryForObject(
            "SELECT reward_snapshot_json::text FROM mission_progress WHERE learner_id = ? AND mission_id = '10000000-0000-0000-0000-000000000004'",
            String.class,
            learnerId
        );
        assertThat(beforeClaimSnapshot).isNull();

        mvc.perform(post("/api/v1/gamification/missions/{id}/claim", "10000000-0000-0000-0000-000000000004")
                .with(learnerAuth)
                .header("Idempotency-Key", "claim-grammar-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.xpAwarded").value(10))
            .andExpect(jsonPath("$.diamondsAwarded").value(2))
            .andExpect(jsonPath("$.diamondBalance").value(2));

        // Replaying with the same Idempotency-Key succeeds cleanly without double awarding
        mvc.perform(post("/api/v1/gamification/missions/{id}/claim", "10000000-0000-0000-0000-000000000004")
                .with(learnerAuth)
                .header("Idempotency-Key", "claim-grammar-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diamondBalance").value(2));

        // Calling claim again without replay fails with 409
        mvc.perform(post("/api/v1/gamification/missions/{id}/claim", "10000000-0000-0000-0000-000000000004")
                .with(learnerAuth))
            .andExpect(status().isConflict());

        // DAILY_GRAMMAR_5: 10 XP, 2 Diamonds, snapshot populated
        String snapshotJson = jdbc.queryForObject(
            "SELECT reward_snapshot_json::text FROM mission_progress WHERE learner_id = ? AND mission_id = '10000000-0000-0000-0000-000000000004'",
            String.class,
            learnerId
        );
        var snapshotNode = mapper.readTree(snapshotJson);
        assertThat(snapshotNode.get("xp").asInt()).isEqualTo(10);
        assertThat(snapshotNode.get("diamonds").asInt()).isEqualTo(2);
        assertThat(snapshotNode.get("missionCode").asString()).isEqualTo("DAILY_GRAMMAR_5");

        // 2. Cutover policy on LessonRewardListener
        // Pre-cutover event (< 2026-11-25T00:00:00Z) awards legacy 2 diamonds
        lessonRewardListener.on(new LessonActivityCompletedEvent(
            UUID.randomUUID(), learnerId, UUID.randomUUID(), UUID.randomUUID(),
            "SHADOWING", 85, Instant.parse("2026-11-24T23:59:59Z")
        ));
        // Post-cutover event (>= 2026-11-25T00:00:00Z) awards 0 diamonds when disabled
        lessonRewardListener.on(new LessonActivityCompletedEvent(
            UUID.randomUUID(), learnerId, UUID.randomUUID(), UUID.randomUUID(),
            "SHADOWING", 95, Instant.parse("2026-11-25T12:00:00Z")
        ));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM diamond_transaction WHERE learner_id = ? AND transaction_type = 'LESSON_REWARD'",
            Integer.class,
            learnerId
        )).isEqualTo(1);

        // 3. Achievements: FIRST_LESSON (badge-only 0 diamonds, 20 XP) & FIVE_LESSONS (5 distinct lessons -> 50 XP, 5 diamonds)
        UUID repeatedLesson = UUID.randomUUID();
        missionListener.lesson(new LessonCompletedEvent(UUID.randomUUID(), learnerId, repeatedLesson, twoDaysAgo));
        // Completing the same lesson again must NOT advance FIVE_LESSONS past 1/5
        missionListener.lesson(new LessonCompletedEvent(UUID.randomUUID(), learnerId, repeatedLesson, twoDaysAgo));

        Integer fiveLessonsProgress = jdbc.queryForObject(
            """
            SELECT progress FROM learner_achievement
            WHERE learner_id = ? AND achievement_id = '40000000-0000-0000-0000-000000000002'
            """,
            Integer.class,
            learnerId
        );
        assertThat(fiveLessonsProgress).isEqualTo(1);
        // FIRST_LESSON has diamond_reward = 0, so no ACHIEVEMENT_REWARD diamond tx yet
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM diamond_transaction WHERE learner_id = ? AND transaction_type = 'ACHIEVEMENT_REWARD'",
            Integer.class,
            learnerId
        )).isZero();

        for (int i = 0; i < 4; i++) {
            missionListener.lesson(new LessonCompletedEvent(UUID.randomUUID(), learnerId, UUID.randomUUID(), twoDaysAgo));
        }
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM diamond_transaction WHERE learner_id = ? AND transaction_type = 'ACHIEVEMENT_REWARD'",
            Integer.class,
            learnerId
        )).isEqualTo(1);

        // 4. StreakService UTC across same-day, midnight, late/out-of-order, and skipped-day
        UUID streakLearner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", streakLearner, "streak-" + streakLearner);
        streakService.recordActivity(UUID.randomUUID(), streakLearner, Instant.parse("2026-11-20T01:00:00Z"));
        var sameDay = streakService.recordActivity(UUID.randomUUID(), streakLearner, Instant.parse("2026-11-20T22:00:00Z"));
        assertThat(sameDay.currentStreak()).isEqualTo(1);
        assertThat(sameDay.longestStreak()).isEqualTo(1);

        var midnight = streakService.recordActivity(UUID.randomUUID(), streakLearner, Instant.parse("2026-11-21T00:00:01Z"));
        assertThat(midnight.currentStreak()).isEqualTo(2);

        // Late/out-of-order fact for Nov 19 arriving after Nov 21 heals streak to 3!
        var lateOutOfOrder = streakService.recordActivity(UUID.randomUUID(), streakLearner, Instant.parse("2026-11-19T23:59:59Z"));
        assertThat(lateOutOfOrder.currentStreak()).isEqualTo(3);
        assertThat(lateOutOfOrder.longestStreak()).isEqualTo(3);
        assertThat(lateOutOfOrder.lastStudyDate()).isEqualTo(LocalDate.of(2026, 11, 21));

        // Skipped Nov 22, study on Nov 23 -> currentStreak resets to 1, longest stays 3
        var skippedDay = streakService.recordActivity(UUID.randomUUID(), streakLearner, Instant.parse("2026-11-23T08:00:00Z"));
        assertThat(skippedDay.currentStreak()).isEqualTo(1);
        assertThat(skippedDay.longestStreak()).isEqualTo(3);

        // Trigger STREAK_3_DAYS achievement on main learner across 3 UTC days ending today
        missionListener.lesson(new LessonCompletedEvent(
            UUID.randomUUID(), learnerId, UUID.randomUUID(), yesterday
        ));
        missionListener.lesson(new LessonCompletedEvent(
            UUID.randomUUID(), learnerId, UUID.randomUUID(), today
        ));

        // 5. Admin diamond adjustment requires reason + idempotencyKey and detects conflict
        mvc.perform(post("/api/v1/admin/gamification/learners/{id}/diamonds", learnerId)
                .with(adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":15,\"reason\":\"\",\"idempotencyKey\":\"adj-1\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/admin/gamification/learners/{id}/diamonds", learnerId)
                .with(adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":15,\"reason\":\"QA bonus\",\"idempotencyKey\":\"adj-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(15));

        // Idempotent replay succeeds with same balance
        mvc.perform(post("/api/v1/admin/gamification/learners/{id}/diamonds", learnerId)
                .with(adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":15,\"reason\":\"QA bonus\",\"idempotencyKey\":\"adj-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(15));

        // Conflicting payload with same idempotencyKey fails with 409
        mvc.perform(post("/api/v1/admin/gamification/learners/{id}/diamonds", learnerId)
                .with(adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":20,\"reason\":\"QA bonus\",\"idempotencyKey\":\"adj-1\"}"))
            .andExpect(status().isConflict());

        // 6. Learner read endpoints return accurate summary, missions, achievements, and ledger
        mvc.perform(get("/api/v1/gamification/summary").with(learnerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.level").value(2))
            .andExpect(jsonPath("$.currentStreak").value(3))
            .andExpect(jsonPath("$.longestStreak").value(3));

        mvc.perform(get("/api/v1/gamification/missions").with(learnerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").isNotEmpty());

        mvc.perform(get("/api/v1/gamification/achievements").with(learnerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/api/v1/gamification/ledger").with(learnerAuth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").isNumber())
            .andExpect(jsonPath("$.total").isNumber())
            .andExpect(jsonPath("$.hasMore").isBoolean())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void preventsConcurrentNegativeBalanceAndAdvancesShadowingVocabToeicMetrics() throws Exception {
        UUID learnerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learnerId, "concurrent-" + learnerId);
        var adminService = context.getBean(GamificationAdminService.class);
        adminService.adjustDiamonds(learnerId, 100, "Initial credit", "conc-init");

        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var c1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    adminService.adjustDiamonds(learnerId, -70, "Debit 1", "conc-debit-1");
                    return 200;
                } catch (StateConflictException ex) {
                    return 409;
                }
            });
            var c2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    adminService.adjustDiamonds(learnerId, -70, "Debit 2", "conc-debit-2");
                    return 200;
                } catch (StateConflictException ex) {
                    return 409;
                }
            });
            ready.await();
            start.countDown();
            List<Integer> outcomes = List.of(c1.get(), c2.get());
            assertThat(outcomes).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT cached_balance FROM diamond_wallet WHERE learner_id = ?", Integer.class, learnerId))
            .isEqualTo(30);

        UUID shadowMissionId = UUID.randomUUID();
        UUID vocabAchId = UUID.randomUUID();
        UUID toeicAchId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO mission_definition(id, code, title, recurrence, metric, target, diamond_reward, rule_version, enabled)
            VALUES (?, 'DAILY_SHADOWING_TEST', 'Shadowing Test', 'DAILY', 'SHADOWING_SENTENCE', 2, 5, 'v1', true)
            """, shadowMissionId);
        jdbc.update("""
            INSERT INTO achievement_definition(id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled)
            VALUES (?, 'VOCAB_TEST_ACH', 'Vocab Ach', 'Desc', 'VOCABULARY_REVIEW', 1, 10, 2, 'v1', true),
                   (?, 'TOEIC_TEST_ACH', 'TOEIC Ach', 'Desc', 'TOEIC_DRILL_COMPLETED', 1, 10, 2, 'v1', true)
            """, vocabAchId, toeicAchId);

        UUID attemptId = UUID.randomUUID();
        missionListener.shadowing(new ShadowingAssessedEvent(attemptId, learnerId, 0, 5, 90));
        missionListener.shadowing(new ShadowingAssessedEvent(attemptId, learnerId, 0, 5, 90));
        assertThat(jdbc.queryForObject(
            "SELECT progress FROM mission_progress WHERE learner_id = ? AND mission_id = ?",
            Integer.class, learnerId, shadowMissionId
        )).isEqualTo(1);

        missionListener.vocabulary(new VocabularyReviewCompletedEvent(
            UUID.randomUUID(), learnerId, UUID.randomUUID(), null, "GOOD", true, Instant.now()
        ));
        missionListener.toeic(new ToeicAttemptCompletedEvent(
            learnerId, UUID.randomUUID(), UUID.randomUUID(), "DRILL", 5, 5, 0, 0, null, null, Instant.now()
        ));

        assertThat(jdbc.queryForObject(
            "SELECT progress FROM learner_achievement WHERE learner_id = ? AND achievement_id = ?",
            Integer.class, learnerId, vocabAchId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT progress FROM learner_achievement WHERE learner_id = ? AND achievement_id = ?",
            Integer.class, learnerId, toeicAchId
        )).isEqualTo(1);
    }

    private static RequestPostProcessor auth(String subject, String role) {
        return jwt()
            .jwt(token -> token.subject(subject).claim("email", subject + "@example.test"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
