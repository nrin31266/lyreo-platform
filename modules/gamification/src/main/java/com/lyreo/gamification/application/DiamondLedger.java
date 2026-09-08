package com.lyreo.gamification.application;

import com.lyreo.gamification.domain.DiamondTransaction;
import java.util.Optional;
import java.util.UUID;

public interface DiamondLedger {
    boolean existsByIdempotencyKey(String idempotencyKey);
    DiamondTransaction append(DiamondTransaction transaction);
    int balance(UUID learnerId);
}
