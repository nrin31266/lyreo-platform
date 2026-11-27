package com.lyreo.platform.config.infrastructure;

import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC implementation of durable consumer fact deduplication.
 */
public final class JdbcConsumerReceiptStore implements ConsumerReceiptStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcConsumerReceiptStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean tryRecord(String consumer, UUID factId) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(factId, "factId");
        int inserted = jdbc.update("""
            INSERT INTO consumer_event_receipt(consumer, fact_id)
            VALUES (:consumer, :factId)
            ON CONFLICT (consumer, fact_id) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("consumer", consumer)
            .addValue("factId", factId));
        return inserted == 1;
    }
}
