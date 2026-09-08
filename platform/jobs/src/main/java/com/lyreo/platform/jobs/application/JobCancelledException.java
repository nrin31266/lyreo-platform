package com.lyreo.platform.jobs.application;

public final class JobCancelledException extends RuntimeException {
    public JobCancelledException(String message) {
        super(message);
    }
}
