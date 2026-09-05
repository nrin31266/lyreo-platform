package com.lyreo.notification.application;

import java.util.UUID;
import java.util.function.Consumer;

/** Framework-free subscription port used by transports such as SSE. */
public interface JobProgressFeed {
    Subscription subscribe(Consumer<JobProgressMessage> consumer);

    record JobProgressMessage(UUID jobId, String status, String currentStep, int progressPercent) {}

    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
