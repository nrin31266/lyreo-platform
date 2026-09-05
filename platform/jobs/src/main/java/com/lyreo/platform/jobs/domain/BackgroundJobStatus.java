package com.lyreo.platform.jobs.domain;

public enum BackgroundJobStatus {
    QUEUED, RUNNING, RETRY_WAIT, CANCEL_REQUESTED, CANCELLED, SUCCEEDED, FAILED
}
