package com.lyreo.lesson.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.lyreo.ai.application.AiInvocationService;
import com.lyreo.ai.application.AiRoutingSnapshotService;
import com.lyreo.lesson.application.CreateLessonBuildService;
import com.lyreo.lesson.application.DictationScoringPolicy;
import com.lyreo.lesson.application.LessonActivityWriter;
import com.lyreo.lesson.application.LessonMediaUploadService;
import com.lyreo.lesson.application.LessonBuildJobHandler;
import com.lyreo.lesson.application.LessonBuildPlanner;
import com.lyreo.lesson.application.LessonBuildStateRepository;
import com.lyreo.lesson.application.LessonEnrichmentWriter;
import com.lyreo.lesson.application.LessonPreviewQuery;
import com.lyreo.lesson.application.LessonPreviewService;
import com.lyreo.lesson.application.LessonPracticeRepository;
import com.lyreo.lesson.application.LessonPracticeService;
import com.lyreo.lesson.application.LessonProcessingPolicyRepository;
import com.lyreo.lesson.application.LessonPromptFactory;
import com.lyreo.lesson.application.LessonRepository;
import com.lyreo.lesson.application.LessonSourceMaterializer;
import com.lyreo.platform.config.application.RuntimeConfigService;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class LessonConfiguration {

    @Bean
    LessonRepository lessonRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcLessonRepository(jdbc);
    }

    @Bean
    LessonPreviewQuery lessonPreviewQuery(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper
    ) {
        return new JdbcLessonPreviewQuery(jdbc, mapper);
    }

    @Bean
    LessonPreviewService lessonPreviewService(
        LessonPreviewQuery query,
        BackgroundJobService jobs
    ) {
        return new LessonPreviewService(query, jobs);
    }

    @Bean
    LessonBuildStateRepository lessonBuildStateRepository(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper
    ) {
        return new JdbcLessonBuildStateRepository(jdbc, mapper);
    }

    @Bean
    LessonActivityWriter lessonActivityWriter(NamedParameterJdbcTemplate jdbc) {
        return new JdbcLessonActivityWriter(jdbc);
    }

    @Bean
    LessonPracticeRepository lessonPracticeRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcLessonPracticeRepository(jdbc);
    }

    @Bean
    DictationScoringPolicy dictationScoringPolicy() {
        return new DictationScoringPolicy();
    }

    @Bean
    LessonPracticeService lessonPracticeService(
        LessonPracticeRepository repository,
        DictationScoringPolicy scoring,
        ApplicationEventPublisher events,
        ObjectMapper mapper
    ) {
        return new LessonPracticeService(repository, scoring, events, mapper);
    }

    @Bean
    LessonEnrichmentWriter lessonEnrichmentWriter(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper
    ) {
        return new JdbcLessonEnrichmentWriter(jdbc, mapper);
    }

    @Bean
    LessonMediaUploadService lessonMediaUploadService(
        ObjectStoragePort storage,
        @Value("${lyreo.lesson.media.max-audio-bytes:104857600}") long maxAudioBytes,
        @Value("${lyreo.lesson.media.max-image-bytes:10485760}") long maxImageBytes
    ) {
        return new LessonMediaUploadService(
            storage,
            maxAudioBytes,
            maxImageBytes,
            Duration.ofMinutes(15)
        );
    }

    @Bean
    LessonProcessingPolicyRepository lessonProcessingPolicyRepository(
        RuntimeConfigService runtimeConfig
    ) {
        return new RuntimeLessonProcessingPolicyRepository(runtimeConfig);
    }

    @Bean
    LessonSourceMaterializer lessonSourceMaterializer(
        ObjectStoragePort storage,
        @Value("${lyreo.lesson.youtube.enabled:false}") boolean youtubeEnabled,
        @Value("${lyreo.lesson.youtube.command:yt-dlp}") String youtubeCommand,
        @Value("${lyreo.lesson.youtube.timeout-seconds:300}") long timeoutSeconds
    ) {
        return new ExternalCommandYoutubeSourceMaterializer(
            storage,
            youtubeEnabled,
            youtubeCommand,
            Duration.ofSeconds(Math.max(30L, timeoutSeconds))
        );
    }

    @Bean
    LessonBuildPlanner lessonBuildPlanner() {
        return new LessonBuildPlanner();
    }

    @Bean
    LessonPromptFactory lessonPromptFactory() {
        return new LessonPromptFactory();
    }

    @Bean
    CreateLessonBuildService createLessonBuildService(
        LessonRepository lessons,
        LessonBuildPlanner planner,
        LessonBuildStateRepository state,
        LessonProcessingPolicyRepository processingPolicy,
        BackgroundJobService jobs,
        AiRoutingSnapshotService aiRoutingSnapshot,
        ObjectMapper mapper
    ) {
        return new CreateLessonBuildService(
            lessons,
            planner,
            state,
            processingPolicy,
            jobs,
            aiRoutingSnapshot,
            mapper
        );
    }

    @Bean
    LessonBuildJobHandler lessonBuildJobHandler(
        LessonRepository lessons,
        LessonBuildStateRepository state,
        AiInvocationService ai,
        ObjectStoragePort storage,
        LessonActivityWriter activityWriter,
        LessonEnrichmentWriter enrichmentWriter,
        LessonPromptFactory prompts,
        LessonSourceMaterializer sourceMaterializer,
        ObjectMapper mapper
    ) {
        return new LessonBuildJobHandler(
            lessons,
            state,
            ai,
            storage,
            activityWriter,
            enrichmentWriter,
            prompts,
            sourceMaterializer,
            mapper
        );
    }
}
