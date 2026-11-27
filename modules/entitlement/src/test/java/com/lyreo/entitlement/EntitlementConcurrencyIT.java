package com.lyreo.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureAccess;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.api.QuotaDecision;
import com.lyreo.entitlement.api.QuotaExceededException;
import com.lyreo.entitlement.api.QuotaReservation;
import com.lyreo.entitlement.application.internal.DefaultEntitlementService;
import com.lyreo.entitlement.application.internal.LearnerPlanAdminService;
import com.lyreo.entitlement.domain.LearnerPlan;
import com.lyreo.entitlement.domain.LearnerPlan.Tier;
import com.lyreo.entitlement.infrastructure.persistence.JdbcEntitlementAuditRepository;
import com.lyreo.entitlement.infrastructure.persistence.JdbcFeatureRepository;
import com.lyreo.entitlement.infrastructure.persistence.JdbcLearnerPlanRepository;
import com.lyreo.entitlement.infrastructure.persistence.JdbcQuotaPolicyRepository;
import com.lyreo.entitlement.infrastructure.persistence.JdbcUsageQuotaRepository;
import com.lyreo.identity.application.AppUserProvisioningService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

class EntitlementConcurrencyIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    private static final UUID ARCHIVED_PLUS_LEARNER = UUID.randomUUID();
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate namedJdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Path migrations = Path.of("../../apps/core-service/src/main/resources/db/migration").toAbsolutePath().normalize();
        var flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + migrations);
        flyway.target("011").load().migrate();

        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, 'archived-plus')", ARCHIVED_PLUS_LEARNER);
        jdbc.update("""
            INSERT INTO user_entitlement_grant (
                id, user_id, feature_key, status, valid_from, valid_until, revoked_at, revoked_by, source_type, created_by
            ) VALUES
                (gen_random_uuid(), ?, 'lesson.plus-lessons', 'ACTIVE', '2026-11-01T00:00Z', '2026-11-09T00:00Z', NULL, NULL, 'MANUAL', 'test'),
                (gen_random_uuid(), ?, 'grammar.advanced', 'REVOKED', '2026-11-02T00:00Z', '2026-11-21T00:00Z', '2026-11-12T00:00Z', 'test', 'MANUAL', 'test'),
                (gen_random_uuid(), ?, 'toeic.full-access', 'ACTIVE', '2026-11-15T00:00Z', NULL, NULL, NULL, 'MANUAL', 'test')
            """, ARCHIVED_PLUS_LEARNER, ARCHIVED_PLUS_LEARNER, ARCHIVED_PLUS_LEARNER);
        flyway.target("latest").load().migrate();
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        txManager = new JdbcTransactionManager(dataSource);
    }

    private EntitlementService transactionalService(AtomicReference<Instant> nowRef) {
        Clock mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };
        DefaultEntitlementService target = new DefaultEntitlementService(
            new JdbcFeatureRepository(namedJdbc),
            new JdbcLearnerPlanRepository(namedJdbc),
            new JdbcQuotaPolicyRepository(namedJdbc),
            new JdbcUsageQuotaRepository(namedJdbc),
            new JdbcEntitlementAuditRepository(namedJdbc, new ObjectMapper()),
            mutableClock
        );
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (EntitlementService) proxy.getProxy();
    }

    private LearnerPlanAdminService adminService(AtomicReference<Instant> nowRef) {
        Clock mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };
        var users = mock(AppUserProvisioningService.class);
        when(users.exists(any(UUID.class))).thenReturn(true);
        var target = new LearnerPlanAdminService(new JdbcLearnerPlanRepository(namedJdbc), users, mutableClock);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (LearnerPlanAdminService) proxy.getProxy();
    }

    @Test
    void concurrentLastSlotAllowsExactlyOneAndReplaysIdempotentOperation() throws Exception {
        UUID learner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learner, "sub-" + learner);

        Instant baseTime = Instant.parse("2026-11-25T12:00:00Z");
        AtomicReference<Instant> nowRef = new AtomicReference<>(baseTime);
        EntitlementService service = transactionalService(nowRef);
        FeatureKey shadowing = FeatureKey.of("speech.shadowing");

        // Consume 4 of 5 FREE daily slots, leaving exactly 1 remaining
        for (int i = 1; i <= 4; i++) {
            QuotaDecision decision = service.consumeQuota(learner, shadowing, "warmup-" + i);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remaining()).isEqualTo(5 - i);
        }

        // Two concurrent threads race for the 5th (last) slot
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> taskA = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return service.reserveQuota(learner, shadowing, "concurrent-op-A");
                } catch (QuotaExceededException ex) {
                    return ex;
                }
            };
            Callable<Object> taskB = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return service.reserveQuota(learner, shadowing, "concurrent-op-B");
                } catch (QuotaExceededException ex) {
                    return ex;
                }
            };
            Future<Object> f1 = executor.submit(taskA);
            Future<Object> f2 = executor.submit(taskB);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Object r1 = f1.get(10, TimeUnit.SECONDS);
            Object r2 = f2.get(10, TimeUnit.SECONDS);
            List<Object> results = List.of(r1, r2);

            long successes = results.stream().filter(QuotaReservation.class::isInstance).count();
            long rejections = results.stream().filter(QuotaExceededException.class::isInstance).count();
            assertThat(successes).isEqualTo(1);
            assertThat(rejections).isEqualTo(1);

            QuotaReservation winner = results.stream()
                .filter(QuotaReservation.class::isInstance)
                .map(QuotaReservation.class::cast)
                .findFirst()
                .orElseThrow();
            QuotaExceededException loser = results.stream()
                .filter(QuotaExceededException.class::isInstance)
                .map(QuotaExceededException.class::cast)
                .findFirst()
                .orElseThrow();

            assertThat(loser.limitType()).isEqualTo("PERIOD_USAGE");
            assertThat(loser.limit()).isEqualTo(5);
            assertThat(loser.remaining()).isZero();
            assertThat(loser.resetAt()).isEqualTo(Instant.parse("2026-11-26T00:00:00Z"));

            // Retry with winner's operationId replays exact same reservation idempotently
            QuotaReservation replayed = service.reserveQuota(learner, shadowing, winner.operationId());
            assertThat(replayed.reservationId()).isEqualTo(winner.reservationId());

            // Releasing the reservation restores the 5th slot
            service.releaseQuota(winner.reservationId(), "PROVIDER_ERROR");
            QuotaReservation afterRelease = service.reserveQuota(learner, shadowing, "after-release-op");
            assertThat(afterRelease.remaining()).isZero();
            service.settleQuota(afterRelease.reservationId());

            // UTC day rollover resets remaining to 5
            nowRef.set(Instant.parse("2026-11-26T00:00:01Z"));
            FeatureAccess nextDay = service.evaluateFeature(learner, shadowing);
            assertThat(nextDay.remaining()).isEqualTo(5);
            assertThat(nextDay.resetAt()).isEqualTo(Instant.parse("2026-11-27T00:00:00Z"));
        }
    }

    @Test
    void mergesOverlappingFeatureGrantsAndKeepsGapsInPlanHistory() {
        var now = new AtomicReference<>(Instant.parse("2026-11-13T00:00:00Z"));
        var admin = adminService(now);
        var beforeNextPeriod = admin.read(ARCHIVED_PLUS_LEARNER);
        assertThat(beforeNextPeriod.tier()).isEqualTo(Tier.FREE);
        assertThat(beforeNextPeriod.history()).extracting(LearnerPlan.Change::changedAt)
            .containsExactly(Instant.parse("2026-11-12T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

        now.set(Instant.parse("2026-11-16T00:00:00Z"));
        assertThat(admin.read(ARCHIVED_PLUS_LEARNER).history()).extracting(LearnerPlan.Change::tier)
            .containsExactly(Tier.PLUS, Tier.FREE, Tier.PLUS);
    }

    @Test
    void concurrentPlanRequestsProduceOneUpgrade() throws Exception {
        UUID learner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learner, "plan-" + learner);
        var now = new AtomicReference<>(Instant.parse("2026-11-26T00:00:00Z"));
        var admin = adminService(now);
        Callable<LearnerPlan> upgrade = () -> admin.change(learner, Tier.PLUS, null, "admin-1");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(upgrade);
            var second = executor.submit(upgrade);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(admin.read(learner).history()).extracting(LearnerPlan.Change::tier).containsExactly(Tier.PLUS);
    }

    @Test
    void enforcesCapacityAndFeatureAccessAcrossPlanChanges() {
        UUID learner = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learner, "cap-" + learner);

        Instant t0 = Instant.parse("2026-11-25T10:00:00Z");
        AtomicReference<Instant> nowRef = new AtomicReference<>(t0);
        EntitlementService service = transactionalService(nowRef);
        LearnerPlanAdminService admin = adminService(nowRef);

        FeatureKey savedCards = FeatureKey.of("vocabulary.saved-cards");
        FeatureKey plusPacks = FeatureKey.of("vocabulary.plus-packs");

        // FREE capacity limit is 100
        service.requireCapacity(learner, savedCards, 99, "save-99");
        assertThatThrownBy(() -> service.requireCapacity(learner, savedCards, 100, "save-100"))
            .isInstanceOf(QuotaExceededException.class)
            .satisfies(ex -> {
                QuotaExceededException q = (QuotaExceededException) ex;
                assertThat(q.limitType()).isEqualTo("CAPACITY");
                assertThat(q.limit()).isEqualTo(100);
            });

        var upgraded = admin.change(learner, Tier.PLUS, 30, "admin-1");
        assertThat(upgraded.validUntil()).isEqualTo(t0.plusSeconds(30 * 86400));
        assertThat(service.hasFeature(learner, plusPacks)).isTrue();
        service.requireCapacity(learner, savedCards, 100, "plus-save-100");
        assertThat(admin.change(learner, Tier.PLUS, 30, "admin-1")).isEqualTo(upgraded);

        nowRef.set(t0.plusSeconds(60));
        var downgraded = admin.change(learner, Tier.FREE, null, "admin-1");
        assertThat(service.hasFeature(learner, plusPacks)).isFalse();
        assertThat(downgraded.history()).extracting(LearnerPlan.Change::tier).containsExactly(Tier.FREE, Tier.PLUS);
        assertThat(downgraded.history().getFirst().changedBy()).isEqualTo("admin-1");

        nowRef.set(t0.plusSeconds(120));
        var renewed = admin.change(learner, Tier.PLUS, 30, "admin-1");
        assertThat(service.hasFeature(learner, plusPacks)).isTrue();
        nowRef.set(renewed.validUntil());
        assertThat(service.hasFeature(learner, plusPacks)).isFalse();
        assertThat(admin.read(learner).history()).extracting(LearnerPlan.Change::tier)
            .containsExactly(Tier.FREE, Tier.PLUS, Tier.FREE, Tier.PLUS);
    }
}
