package com.lyreo.platform.jobs.infrastructure;

import com.lyreo.platform.jobs.application.BackgroundJobRepository;
import com.lyreo.platform.jobs.application.JobCancelledException;
import com.lyreo.platform.jobs.application.JobExecutionContext;
import com.lyreo.platform.jobs.application.JobLeaseLostException;
import com.lyreo.platform.jobs.application.JobStateTransitionService;
import com.lyreo.platform.jobs.application.PermanentJobException;
import com.lyreo.platform.jobs.application.JobHandler;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Durable PostgreSQL job worker.
 *
 * <p>The lease heartbeat intentionally runs independently from handler progress updates. AI calls can
 * legitimately take longer than the lease duration; relying only on progress callbacks would allow
 * another worker to recover and run the same job while the first worker is still blocked in an
 * external call.</p>
 */
public final class BackgroundJobWorker implements AutoCloseable {

    private final BackgroundJobRepository repository;
    private final Map<String, JobHandler> handlers;
    private final JobStateTransitionService transitions;
    private final String workerId;
    private final Duration lease;
    private final int claimBatchSize;
    private final ScheduledExecutorService heartbeatExecutor;

    public BackgroundJobWorker(
        BackgroundJobRepository repository,
        List<JobHandler> handlers,
        JobStateTransitionService transitions,
        String workerId,
        Duration lease,
        int claimBatchSize
    ) {
        this.repository = repository;
        this.handlers = handlers.stream()
            .collect(Collectors.toUnmodifiableMap(JobHandler::jobType, Function.identity()));
        this.transitions = transitions;
        this.workerId = workerId;
        this.lease = lease;
        this.claimBatchSize = claimBatchSize;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "lyreo-job-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public int runOnce() {
        List<BackgroundJob> jobs = repository.claim(workerId, claimBatchSize, lease);
        // A small batch is processed sequentially by this starter. Scale by running more Core
        // instances or, later, by introducing a bounded executor without changing the DB protocol.
        jobs.forEach(this::runJob);
        return jobs.size();
    }

    public int recoverExpiredLeases() {
        return transitions.recoverExpiredLeases(Instant.now());
    }

    private void runJob(BackgroundJob job) {
        JobHandler handler = handlers.get(job.jobType());
        if (handler == null) {
            transitions.failed(
                job, workerId, "HANDLER_NOT_FOUND",
                "No JobHandler registered for " + job.jobType(),
                job.currentStep(), job.progressPercent()
            );
            return;
        }

        var context = new Context(job);
        ScheduledFuture<?> heartbeat = startHeartbeat(job.id());
        try {
            context.throwIfCancellationRequested();
            handler.execute(job, context);
            context.throwIfCancellationRequested();
            requireLease(transitions.succeeded(job, workerId), job.id());
        } catch (JobCancelledException cancelled) {
            transitions.cancelled(
                job, workerId, context.currentStep(), context.currentPercent()
            );
        } catch (JobLeaseLostException leaseLost) {
            // A recovered job now belongs to another worker. Fencing prevents this stale
            // worker from overwriting the new owner's state. Do not mark it failed.
        } catch (PermanentJobException permanent) {
            transitions.failed(
                job, workerId, permanent.errorCode(), safeMessage(permanent),
                context.currentStep(), context.currentPercent()
            );
        } catch (Exception failure) {
            BackgroundJob fresh = repository.findById(job.id()).orElse(job);
            if (fresh.cancellationRequested()) {
                transitions.cancelled(
                    job, workerId, fresh.currentStep(), fresh.progressPercent()
                );
            } else if (fresh.attemptCount() < fresh.maxAttempts()) {
                long delaySeconds = Math.min(300L, 5L * (1L << Math.min(6, Math.max(0, fresh.attemptCount() - 1))));
                transitions.retryWaiting(
                    job, workerId, Instant.now().plusSeconds(delaySeconds),
                    "JOB_EXECUTION_FAILED", safeMessage(failure),
                    fresh.currentStep(), fresh.progressPercent()
                );
            } else {
                transitions.failed(
                    job, workerId, "JOB_EXECUTION_FAILED", safeMessage(failure),
                    fresh.currentStep(), fresh.progressPercent()
                );
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(UUID jobId) {
        long everyMs = Math.max(1_000L, lease.toMillis() / 3L);
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                // Context performs the authoritative fencing check before every durable step.
                repository.heartbeat(jobId, workerId, lease);
            } catch (RuntimeException ignored) {
                // A transient heartbeat failure is deliberately not allowed to kill the handler.
                // If failures persist, the lease eventually expires and another worker can recover.
            }
        }, everyMs, everyMs, TimeUnit.MILLISECONDS);
    }

    private static void requireLease(boolean updated, UUID jobId) {
        if (!updated) throw new JobLeaseLostException("Worker lease lost for job " + jobId);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return throwable.getClass().getSimpleName();
        return message.substring(0, Math.min(message.length(), 2000));
    }

    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    private final class Context implements JobExecutionContext {
        private final BackgroundJob job;
        private final AtomicBoolean leaseLost = new AtomicBoolean(false);
        private volatile String currentStep;
        private volatile int currentPercent;

        private Context(BackgroundJob job) {
            this.job = job;
            this.currentStep = job.currentStep();
            this.currentPercent = job.progressPercent();
        }

        @Override
        public void heartbeat() {
            boolean owned = repository.heartbeat(job.id(), workerId, lease);
            if (!owned) {
                leaseLost.set(true);
                throw new JobLeaseLostException("Worker lease lost for job " + job.id());
            }
        }

        @Override
        public void progress(String step, int percent) {
            assertLease();
            boolean updated = transitions.progress(job, workerId, step, percent);
            if (!updated) {
                leaseLost.set(true);
                throw new JobLeaseLostException("Worker lease lost while updating job " + job.id());
            }
            this.currentStep = step;
            this.currentPercent = Math.max(0, Math.min(100, percent));
            heartbeat();
        }

        @Override
        public boolean isCancellationRequested() {
            assertLease();
            return repository.isCancellationRequested(job.id());
        }

        @Override
        public void throwIfCancellationRequested() {
            assertLease();
            if (repository.isCancellationRequested(job.id())) {
                throw new JobCancelledException("Job cancellation requested");
            }
        }

        @Override
        public UUID jobId() {
            return job.id();
        }

        private void assertLease() {
            if (leaseLost.get()) {
                throw new JobLeaseLostException("Worker lease already lost for job " + job.id());
            }
        }

        private String currentStep() {
            return currentStep;
        }

        private int currentPercent() {
            return currentPercent;
        }
    }

}
