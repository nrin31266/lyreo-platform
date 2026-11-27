package com.lyreo.entitlement.domain;

import java.time.Instant;
import java.util.List;

public record LearnerPlan(Tier tier, Instant validUntil, List<Change> history) {
    public enum Tier { FREE, PLUS }

    public record Change(Tier tier, Instant changedAt, String changedBy) {}
}
