package com.lyreo.platform.jobs.application;

import com.lyreo.platform.jobs.domain.BackgroundJob;

public interface JobHandler {
    String jobType();
    void execute(BackgroundJob job, JobExecutionContext context) throws Exception;
}
