package com.lyreo.platform.bootstrap;

import com.lyreo.platform.jobs.infrastructure.BackgroundJobWorker;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobScheduler {
    private final BackgroundJobWorker worker;

    public JobScheduler(BackgroundJobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${lyreo.jobs.poll-interval-ms:1000}")
    public void poll() {
        worker.runOnce();
    }

    @Scheduled(fixedDelayString = "${lyreo.jobs.recover-interval-ms:30000}")
    public void recoverExpiredLeases() {
        worker.recoverExpiredLeases();
    }
}
