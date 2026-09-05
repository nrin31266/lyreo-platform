package com.lyreo.gamification.infrastructure;

import com.lyreo.gamification.application.DiamondLedger;
import com.lyreo.gamification.domain.DiamondTransaction;
import java.util.Map;
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
                reference_type, reference_id, created_at
            ) VALUES (
                :id, :learnerId, :transactionType, :amount, :idempotencyKey,
                :referenceType, :referenceId, :createdAt
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
                .addValue("createdAt", transaction.createdAt())
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
    public int balance(UUID learnerId) {
        var balances = jdbc.query(
            "SELECT cached_balance FROM diamond_wallet WHERE learner_id = :learnerId",
            Map.of("learnerId", learnerId),
            (rs, rowNumber) -> rs.getInt("cached_balance")
        );
        return balances.isEmpty() ? 0 : balances.getFirst();
    }
}
