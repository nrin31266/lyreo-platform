package com.lyreo.entitlement.api;

import java.time.Instant;
import org.springframework.modulith.NamedInterface;

/** Outcome of a synchronous quota consumption check. */
@NamedInterface(value = "api", propagate = false)
public record QuotaDecision(
    String featureKey,
    String operationId,
    boolean allowed,
    int limit,
    int remaining,
    Instant resetAt,
    String policyVersion
) {}
