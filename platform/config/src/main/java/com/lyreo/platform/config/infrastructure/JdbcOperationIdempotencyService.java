package com.lyreo.platform.config.infrastructure;

import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL-backed idempotency execution guard using transaction-scoped advisory locks.
 */
public class JdbcOperationIdempotencyService implements OperationIdempotencyService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcOperationIdempotencyService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public <T> T execute(
        UUID learnerId,
        String operation,
        String idempotencyKey,
        String requestFingerprint,
        Class<T> responseType,
        Supplier<T> action
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(responseType, "responseType");
        String normalizedKey = idempotencyKey.trim();
        String fingerprintHash = sha256(requestFingerprint == null ? "" : requestFingerprint);

        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource("lockKey", learnerId + ":" + operation + ":" + normalizedKey),
            Object.class
        );

        jdbc.update("""
            DELETE FROM operation_idempotency_record
             WHERE learner_id = :learnerId
               AND operation = :operation
               AND idempotency_key = :key
               AND expires_at <= now()
            """, new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("operation", operation)
            .addValue("key", normalizedKey));

        var existing = jdbc.query("""
            SELECT request_fingerprint, response_json::text AS response_json
              FROM operation_idempotency_record
             WHERE learner_id = :learnerId
               AND operation = :operation
               AND idempotency_key = :key
            """, new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("operation", operation)
            .addValue("key", normalizedKey), (rs, rowNum) -> new StoredRecord(
            rs.getString("request_fingerprint"),
            rs.getString("response_json")
        )).stream().findFirst();

        if (existing.isPresent()) {
            StoredRecord record = existing.get();
            if (!Objects.equals(record.fingerprint(), fingerprintHash)) {
                throw new IdempotencyConflictException(
                    "Idempotency-Key was already used with a different request payload.",
                    normalizedKey
                );
            }
            return deserialize(record.responseJson(), responseType);
        }

        T result = action.get();
        jdbc.update("""
            INSERT INTO operation_idempotency_record(
                learner_id, operation, idempotency_key, request_fingerprint, status_code, response_json
            ) VALUES (
                :learnerId, :operation, :key, :fingerprint, 200, CAST(:responseJson AS jsonb)
            )
            """, new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("operation", operation)
            .addValue("key", normalizedKey)
            .addValue("fingerprint", fingerprintHash)
            .addValue("responseJson", serialize(result)));
        return result;
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize idempotent response", ex);
        }
    }

    private <T> T deserialize(String json, Class<T> responseType) {
        try {
            return mapper.readValue(json, responseType);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to deserialize idempotent response", ex);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record StoredRecord(String fingerprint, String responseJson) {}
}
