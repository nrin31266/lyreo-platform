package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.domain.UsageCounter;
import com.lyreo.entitlement.domain.UsageReservation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Manages atomic periodic usage counters and idempotent quota reservations. */
public interface UsageQuotaRepository {
    void lockOperation(UUID userId, FeatureKey featureKey, String operationId);

    Optional<UsageCounter> findCounter(UUID userId, FeatureKey featureKey, String periodKey);

    Optional<UsageReservation> findReservationByOperation(UUID userId, FeatureKey featureKey, String operationId);

    Optional<UsageReservation> findReservationByIdForUpdate(UUID reservationId);

    void ensureCounterExists(UUID userId, FeatureKey featureKey, String periodKey, Instant resetAt);

    Optional<UsageCounter> tryReserveCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int maxAmount);

    Optional<UsageCounter> tryConsumeCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int maxAmount);

    void insertReservation(UsageReservation reservation);

    boolean markReservationSettled(UUID reservationId, Instant resolvedAt);

    void settleCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int amount);

    boolean markReservationReleased(UUID reservationId, Instant resolvedAt);

    void releaseCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int amount);

    static UsageQuotaRepository noop() {
        return new UsageQuotaRepository() {
            @Override
            public void lockOperation(UUID userId, FeatureKey featureKey, String operationId) {
            }

            @Override
            public Optional<UsageCounter> findCounter(UUID userId, FeatureKey featureKey, String periodKey) {
                return Optional.empty();
            }

            @Override
            public Optional<UsageReservation> findReservationByOperation(
                UUID userId,
                FeatureKey featureKey,
                String operationId
            ) {
                return Optional.empty();
            }

            @Override
            public Optional<UsageReservation> findReservationByIdForUpdate(UUID reservationId) {
                return Optional.empty();
            }

            @Override
            public void ensureCounterExists(UUID userId, FeatureKey featureKey, String periodKey, Instant resetAt) {
            }

            @Override
            public Optional<UsageCounter> tryReserveCounterUnit(
                UUID userId,
                FeatureKey featureKey,
                String periodKey,
                int maxAmount
            ) {
                return Optional.empty();
            }

            @Override
            public Optional<UsageCounter> tryConsumeCounterUnit(
                UUID userId,
                FeatureKey featureKey,
                String periodKey,
                int maxAmount
            ) {
                return Optional.empty();
            }

            @Override
            public void insertReservation(UsageReservation reservation) {
            }

            @Override
            public boolean markReservationSettled(UUID reservationId, Instant resolvedAt) {
                return false;
            }

            @Override
            public void settleCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int amount) {
            }

            @Override
            public boolean markReservationReleased(UUID reservationId, Instant resolvedAt) {
                return false;
            }

            @Override
            public void releaseCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int amount) {
            }
        };
    }
}
