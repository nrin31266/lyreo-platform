package com.lyreo.platform.jobs.application;

/** Aggregate job counts for operational dashboards. */
public record JobHealthCounts(int active, int failedSince, int succeededSince) {
    public static final JobHealthCounts EMPTY = new JobHealthCounts(0, 0, 0);
}
