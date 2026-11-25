package com.lyreo.entitlement.api;

import java.time.Instant;
import org.springframework.modulith.NamedInterface;

/** Current learner access and quota evaluation for a single feature key. */
@NamedInterface(value = "api", propagate = false)
public record FeatureAccess(
    String featureKey,
    boolean granted,
    String tier,
    String limitType,
    Integer limit,
    Integer used,
    Integer remaining,
    Instant resetAt,
    String lockReason,
    String policyVersion
) {}
