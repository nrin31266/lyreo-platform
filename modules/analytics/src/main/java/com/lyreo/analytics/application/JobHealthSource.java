package com.lyreo.analytics.application;

import java.time.Instant;

/** Supplies background-job counts owned by the platform jobs module. */
@FunctionalInterface
public interface JobHealthSource {
    Counts counts(Instant since);

    record Counts(int active, int failedSince, int succeededSince) {}
}
