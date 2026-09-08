package com.lyreo.platform.jobs.infrastructure;

import com.lyreo.platform.jobs.application.BackgroundJobRepository;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.application.JobHandler;
import com.lyreo.platform.jobs.application.JobStateTransitionService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class JobsConfiguration {

    @Bean
    BackgroundJobRepository backgroundJobRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcBackgroundJobRepository(jdbc);
    }

    @Bean
    JobStateTransitionService jobStateTransitionService(
        BackgroundJobRepository repository,
        ApplicationEventPublisher publisher
    ) {
        return new JobStateTransitionService(repository, publisher);
    }

    @Bean
    BackgroundJobService backgroundJobService(
        BackgroundJobRepository repository,
        JobStateTransitionService transitions
    ) {
        return new BackgroundJobService(repository, transitions);
    }

    @Bean
    BackgroundJobWorker backgroundJobWorker(
        BackgroundJobRepository repository,
        JobStateTransitionService transitions,
        List<JobHandler> handlers,
        @Value("${lyreo.jobs.worker-id:${HOSTNAME:local}}") String workerPrefix,
        @Value("${lyreo.jobs.lease-seconds:60}") long leaseSeconds,
        @Value("${lyreo.jobs.claim-batch-size:5}") int claimBatchSize
    ) {
        return new BackgroundJobWorker(
            repository,
            handlers,
            transitions,
            workerPrefix + "-" + UUID.randomUUID(),
            Duration.ofSeconds(leaseSeconds),
            claimBatchSize
        );
    }
}
