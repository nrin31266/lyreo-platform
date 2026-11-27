package com.lyreo.gamification.application.port;

import com.lyreo.gamification.domain.DiamondTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiamondLedger {
    boolean existsByIdempotencyKey(String idempotencyKey);

    DiamondTransaction append(DiamondTransaction transaction);

    int balance(UUID learnerId);

    default Optional<DiamondTransaction> findByIdempotencyKey(String idempotencyKey) {
        return Optional.empty();
    }

    default List<DiamondTransaction> listByLearner(UUID learnerId, int page, int size) {
        return List.of();
    }

    default long countByLearner(UUID learnerId) {
        return 0L;
    }
}
