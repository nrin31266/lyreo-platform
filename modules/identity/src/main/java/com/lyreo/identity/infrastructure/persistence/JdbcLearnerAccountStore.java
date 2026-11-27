package com.lyreo.identity.infrastructure.persistence;

import com.lyreo.contracts.identity.AccountAccessGuard;
import com.lyreo.identity.application.port.LearnerAccountStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcLearnerAccountStore implements LearnerAccountStore, AccountAccessGuard {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate mutex;
    private final TransactionTemplate write;

    public JdbcLearnerAccountStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        mutex = new TransactionTemplate(transactions);
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<Account> find(UUID learnerId) {
        return jdbc.query("""
            SELECT keycloak_subject, account_locked, account_sync_pending
            FROM app_user WHERE id = ?
            """, (rs, n) -> new Account(
                rs.getString(1),
                rs.getBoolean(2),
                rs.getBoolean(3)
            ), learnerId).stream().findFirst();
    }

    @Override
    public boolean canUseToken(String subject, Instant issuedAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS (
                SELECT 1 FROM app_user WHERE keycloak_subject = ?
                  AND (account_locked OR (account_tokens_valid_after IS NOT NULL
                    AND (CAST(? AS timestamptz) IS NULL OR CAST(? AS timestamptz) <= account_tokens_valid_after)))
            )
            """, Boolean.class, subject, timestamp(issuedAt), timestamp(issuedAt)));
    }

    @Override
    public <T> T serialized(UUID learnerId, Supplier<T> action) {
        return mutex.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class, "learner-account:" + learnerId);
            return action.get();
        });
    }

    @Override
    public void change(UUID learnerId, boolean locked, boolean pending, String actor, String action, String reason) {
        // Commit the Core restriction independently of the lock that serializes remote operations.
        write.executeWithoutResult(status -> {
            jdbc.update("""
                UPDATE app_user SET account_locked = ?, account_sync_pending = ?,
                    account_tokens_valid_after = clock_timestamp()
                WHERE id = ?
                """, locked, pending, learnerId);
            jdbc.update("""
                INSERT INTO learner_account_audit(learner_id, actor, action, reason) VALUES (?, ?, ?, ?)
                """, learnerId, actor, action, reason);
        });
    }

    @Override
    public void audit(UUID learnerId, String actor, String action) {
        jdbc.update("""
            INSERT INTO learner_account_audit(learner_id, actor, action) VALUES (?, ?, ?)
            """, learnerId, actor, action);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
