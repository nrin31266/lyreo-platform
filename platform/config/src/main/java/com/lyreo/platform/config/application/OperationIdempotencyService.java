package com.lyreo.platform.config.application;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Durable per-learner operation idempotency gate backed by PostgreSQL.
 */
public interface OperationIdempotencyService {
    <T> T execute(
        UUID learnerId,
        String operation,
        String idempotencyKey,
        String requestFingerprint,
        Class<T> responseType,
        Supplier<T> action
    );
}
