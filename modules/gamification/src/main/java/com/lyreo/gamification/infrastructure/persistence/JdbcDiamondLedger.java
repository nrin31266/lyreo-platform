package com.lyreo.gamification.infrastructure.persistence;

import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.domain.DiamondTransaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcDiamondLedger implements DiamondLedger {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDiamondLedger(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsByIdempotencyKey(String key) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM diamond_transaction WHERE idempotency_key = :key",
            Map.of("key", key),
            Integer.class
        );
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public DiamondTransaction append(DiamondTransaction transaction) {
        int inserted = jdbc.update(
            """
            INSERT INTO diamond_transaction(
                id, learner_id, transaction_type, amount, idempotency_key,
                reference_type, reference_id, created_at, reason, rule_version
            ) VALUES (
                :id, :learnerId, :transactionType, :amount, :idempotencyKey,
                :referenceType, :referenceId, :createdAt, :reason, :ruleVersion
            )
            ON CONFLICT(idempotency_key) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", transaction.id())
                .addValue("learnerId", transaction.learnerId())
                .addValue("transactionType", transaction.type().name())
                .addValue("amount", transaction.amount())
                .addValue("idempotencyKey", transaction.idempotencyKey())
                .addValue("referenceType", transaction.referenceType())
                .addValue("referenceId", transaction.referenceId())
                .addValue("createdAt", Timestamp.from(transaction.createdAt()))
                .addValue("reason", transaction.reason())
                .addValue("ruleVersion", transaction.ruleVersion())
        );

        // Never apply the wallet delta twice when the idempotency key already existed.
        if (inserted == 1) {
            jdbc.update(
                """
                INSERT INTO diamond_wallet(learner_id, cached_balance, updated_at)
                VALUES(:learnerId, :amount, now())
                ON CONFLICT(learner_id) DO UPDATE SET
                    cached_balance = diamond_wallet.cached_balance + :amount,
                    updated_at = now()
                """,
                Map.of("learnerId", transaction.learnerId(), "amount", transaction.amount())
            );
        }
        return transaction;
    }

    @Override
    @Transactional
    public DiamondTransaction appendCheckingNonNegative(DiamondTransaction transaction) {
        jdbc.update(
            """
            INSERT INTO diamond_wallet(learner_id, cached_balance, updated_at)
            VALUES(:learnerId, 0, now())
            ON CONFLICT(learner_id) DO NOTHING
            """,
            Map.of("learnerId", transaction.learnerId())
        );
        Integer lockedBalance = jdbc.queryForObject(
            "SELECT cached_balance FROM diamond_wallet WHERE learner_id = :learnerId FOR UPDATE",
            Map.of("learnerId", transaction.learnerId()),
            Integer.class
        );
        Optional<DiamondTransaction> existing = findByIdempotencyKey(transaction.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        int currentBalance = lockedBalance == null ? 0 : lockedBalance;
        if (currentBalance + transaction.amount() < 0) {
            throw new StateConflictException("Insufficient diamond balance for negative adjustment");
        }
        int inserted = jdbc.update(
            """
            INSERT INTO diamond_transaction(
                id, learner_id, transaction_type, amount, idempotency_key,
                reference_type, reference_id, created_at, reason, rule_version
            ) VALUES (
                :id, :learnerId, :transactionType, :amount, :idempotencyKey,
                :referenceType, :referenceId, :createdAt, :reason, :ruleVersion
            )
            ON CONFLICT(idempotency_key) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", transaction.id())
                .addValue("learnerId", transaction.learnerId())
                .addValue("transactionType", transaction.type().name())
                .addValue("amount", transaction.amount())
                .addValue("idempotencyKey", transaction.idempotencyKey())
                .addValue("referenceType", transaction.referenceType())
                .addValue("referenceId", transaction.referenceId())
                .addValue("createdAt", Timestamp.from(transaction.createdAt()))
                .addValue("reason", transaction.reason())
                .addValue("ruleVersion", transaction.ruleVersion())
        );
        if (inserted == 1) {
            int updated = jdbc.update(
                """
                UPDATE diamond_wallet
                   SET cached_balance = cached_balance + :amount,
                       updated_at = now()
                 WHERE learner_id = :learnerId
                   AND cached_balance + :amount >= 0
                """,
                Map.of("learnerId", transaction.learnerId(), "amount", transaction.amount())
            );
            if (updated != 1) {
                throw new StateConflictException("Insufficient diamond balance for negative adjustment");
            }
        }
        return transaction;
    }

    @Override
    public int balance(UUID learnerId) {
        var balances = jdbc.query(
            "SELECT cached_balance FROM diamond_wallet WHERE learner_id = :learnerId",
            Map.of("learnerId", learnerId),
            (rs, rowNumber) -> rs.getInt("cached_balance")
        );
        return balances.isEmpty() ? 0 : balances.getFirst();
    }

    @Override
    public Optional<DiamondTransaction> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(
            """
            SELECT id, learner_id, transaction_type, amount, idempotency_key,
                   reference_type, reference_id, created_at, reason, rule_version
            FROM diamond_transaction
            WHERE idempotency_key = :key
            """,
            Map.of("key", idempotencyKey),
            (rs, rowNum) -> mapTransaction(rs)
        ).stream().findFirst();
    }

    @Override
    public List<DiamondTransaction> listByLearner(UUID learnerId, int page, int size) {
        return jdbc.query(
            """
            SELECT id, learner_id, transaction_type, amount, idempotency_key,
                   reference_type, reference_id, created_at, reason, rule_version
            FROM diamond_transaction
            WHERE learner_id = :learnerId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """,
            Map.of(
                "learnerId", learnerId,
                "limit", size,
                "offset", (long) page * size
            ),
            (rs, rowNum) -> mapTransaction(rs)
        );
    }

    @Override
    public long countByLearner(UUID learnerId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM diamond_transaction WHERE learner_id = :learnerId",
            Map.of("learnerId", learnerId),
            Long.class
        );
        return count != null ? count : 0L;
    }

    private static DiamondTransaction mapTransaction(ResultSet rs) throws SQLException {
        return new DiamondTransaction(
            rs.getObject("id", UUID.class),
            rs.getObject("learner_id", UUID.class),
            DiamondTransaction.Type.valueOf(rs.getString("transaction_type")),
            rs.getInt("amount"),
            rs.getString("idempotency_key"),
            rs.getString("reference_type"),
            rs.getObject("reference_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("reason"),
            rs.getString("rule_version")
        );
    }
}
