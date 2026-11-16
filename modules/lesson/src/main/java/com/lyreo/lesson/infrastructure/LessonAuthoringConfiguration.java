package com.lyreo.lesson.infrastructure;

import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.lesson.application.authoring.*;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.infrastructure.persistence.JdbcAuthoringRepository;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.storage.ObjectStoragePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class LessonAuthoringConfiguration {
    @Bean com.lyreo.lesson.application.port.AuthoringMediaPort authoringMedia(NamedParameterJdbcTemplate jdbc,ObjectStoragePort storage,ObjectMapper mapper,
        @org.springframework.beans.factory.annotation.Value("${lyreo.ai-service.base-url:http://localhost:8000}") String url,
        @org.springframework.beans.factory.annotation.Value("${lyreo.ai-service.internal-token:disabled}") String token) {
        return new com.lyreo.lesson.infrastructure.integration.AuthoringMedia(jdbc,storage,mapper,url,token);
    }
    @Bean AuthoringRepository authoringRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,BackgroundJobService jobs) {
        return new JdbcAuthoringRepository(jdbc,mapper,jobs);
    }
    @Bean LessonAuthoringService lessonAuthoringService(AuthoringRepository repository,AuthoringSourcePort source,BackgroundJobService jobs,
        @org.springframework.beans.factory.annotation.Value("${lyreo.lesson.timing.reuse-draft:false}") boolean reuseDraft) {
        var service=new LessonAuthoringService(repository,source,jobs);service.setReuseDraftTiming(reuseDraft);return service;
    }
    @Bean AuthoringJobCommitter authoringJobCommitter(AuthoringRepository repository,LessonAuthoringService authoring) {return new AuthoringJobCommitter(repository,authoring);}
    @Bean LessonAuthoringJobHandler lessonAuthoringJobHandler(AuthoringRepository repository,AuthoringSourcePort source,ObjectStoragePort storage,
        AiInvocationService ai,AuthoringJobCommitter committer,com.lyreo.lesson.application.port.AuthoringMediaPort media) {
        var handler=new LessonAuthoringJobHandler(repository,source,storage,ai,committer);handler.setMedia(media);return handler;
    }
}
