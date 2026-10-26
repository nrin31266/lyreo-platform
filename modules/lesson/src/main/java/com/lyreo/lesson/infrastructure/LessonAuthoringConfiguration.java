package com.lyreo.lesson.infrastructure;

import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.lesson.application.authoring.*;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.infrastructure.persistence.JdbcAuthoringRepository;
import com.lyreo.lexicon.api.LexiconLinkService;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.storage.ObjectStoragePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class LessonAuthoringConfiguration {
    @Bean AuthoringRepository authoringRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,BackgroundJobService jobs) {
        return new JdbcAuthoringRepository(jdbc,mapper,jobs);
    }
    @Bean LessonAuthoringService lessonAuthoringService(AuthoringRepository repository,AuthoringSourcePort source,BackgroundJobService jobs,
        @org.springframework.beans.factory.annotation.Value("${lyreo.lesson.timing.reuse-draft:false}") boolean reuseDraft) {
        var service=new LessonAuthoringService(repository,source,jobs);service.setReuseDraftTiming(reuseDraft);return service;
    }
    @Bean AuthoringSemantic authoringSemantic(ObjectMapper mapper,LexiconLinkService lexicon) {return new AuthoringSemantic(mapper,lexicon);}
    @Bean AuthoringJobCommitter authoringJobCommitter(AuthoringRepository repository) {return new AuthoringJobCommitter(repository);}
    @Bean LessonAuthoringJobHandler lessonAuthoringJobHandler(AuthoringRepository repository,AuthoringSourcePort source,ObjectStoragePort storage,
        AiInvocationService ai,AuthoringSemantic semantic,AuthoringJobCommitter committer) {
        return new LessonAuthoringJobHandler(repository,source,storage,ai,semantic,committer);
    }
}
