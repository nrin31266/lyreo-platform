package com.lyreo.notification.infrastructure;

import com.lyreo.notification.application.JobProgressFeed;
import com.lyreo.notification.application.RealtimeNotificationPort;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * Single-node realtime broker for MVP. Durable job state remains in PostgreSQL, so
 * reconnecting clients can always recover with GET /api/v1/jobs/{id}.
 */
public final class InMemoryJobProgressFeed implements JobProgressFeed, RealtimeNotificationPort {
    private final CopyOnWriteArrayList<Consumer<JobProgressMessage>> subscribers =
        new CopyOnWriteArrayList<>();

    @Override
    public Subscription subscribe(Consumer<JobProgressMessage> consumer) {
        subscribers.add(consumer);
        return () -> subscribers.remove(consumer);
    }

    @Override
    public void jobProgress(UUID jobId, String status, String step, int progress) {
        JobProgressMessage message = new JobProgressMessage(
            jobId, status, step == null ? "" : step, progress
        );
        for (Consumer<JobProgressMessage> subscriber : subscribers) {
            try {
                subscriber.accept(message);
            } catch (RuntimeException ignored) {
                // Transport callback is responsible for closing its own subscription.
            }
        }
    }
}
