package com.lyreo.entitlement.api;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** Idempotent usage reservation held across an asynchronous external operation. */
@NamedInterface(value = "api", propagate = false)
public record QuotaReservation(
    UUID reservationId,
    String featureKey,
    String operationId,
    String status,
    int limit,
    int remaining,
    Instant resetAt,
    String policyVersion
) {}
