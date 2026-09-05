package com.lyreo.notification.application;

import com.lyreo.contracts.jobs.JobProgressChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges durable job state changes to the ephemeral realtime feed.
 *
 * <p>Job state is committed to PostgreSQL first. Realtime delivery is intentionally
 * after-commit: an SSE/WebSocket failure must never roll back the durable job transition,
 * and clients should not receive a state that a subsequent GET cannot observe yet.</p>
 */
@Component
public class JobProgressListener {
    private final RealtimeNotificationPort realtime;

    public JobProgressListener(RealtimeNotificationPort realtime) {
        this.realtime = realtime;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(JobProgressChangedEvent event) {
        realtime.jobProgress(
            event.jobId(),
            event.status(),
            event.currentStep(),
            event.progressPercent()
        );
    }
}
