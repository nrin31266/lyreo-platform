package com.lyreo.platform.jobs.application;

import java.util.UUID;

public interface JobExecutionContext {
    void heartbeat();
    void progress(String step, int percent);
    boolean isCancellationRequested();
    UUID jobId();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new JobCancelledException("Job cancellation requested");
        }
    }
}
