package com.lyreo.platform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.ai.api.AiAdminController;
import com.lyreo.ai.application.AiAdminService;
import com.lyreo.ai.application.ProviderSummary;
import com.lyreo.ai.application.RouteSummary;
import com.lyreo.grammar.api.GrammarPracticeController;
import com.lyreo.grammar.application.GrammarPracticeService;
import com.lyreo.grammar.domain.GrammarQuestion;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.identity.application.ProvisionedUser;
import com.lyreo.learner.api.LearnerController;
import com.lyreo.learner.application.LearnerProfileService;
import com.lyreo.learner.application.OnboardingService;
import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import com.lyreo.lesson.api.AdminLessonController;
import com.lyreo.lesson.api.LessonPracticeController;
import com.lyreo.lesson.application.CreateLessonBuildService;
import com.lyreo.lesson.application.LessonPracticeService;
import com.lyreo.lesson.application.LessonPreviewQuery;
import com.lyreo.lesson.application.LessonPreviewService;
import com.lyreo.lesson.application.LessonPreviewView;
import com.lyreo.lesson.domain.LessonBuildPlan;
import com.lyreo.lesson.domain.LessonSourceType;
import com.lyreo.lexicon.api.LexiconController;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import com.lyreo.platform.observability.CorrelationIdFilter;
import com.lyreo.toeic.api.ToeicAttemptController;
import com.lyreo.toeic.application.ToeicAttemptService;
import com.lyreo.vocabulary.api.VocabularyController;
import com.lyreo.vocabulary.application.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Focused contract tests asserting exact wire JSON shapes, field presence/absence,
 * and HTTP response status/header semantics across all Core HTTP controllers.
 */
class HttpWireContractTest {

    private CreateLessonBuildService lessonBuildService;
    private LessonPreviewQuery lessonPreviewQuery;
    private BackgroundJobService backgroundJobService;
    private LessonPreviewService lessonPreviewService;
    private LessonPracticeService lessonPracticeService;
    private GrammarPracticeService grammarPracticeService;
    private ToeicAttemptService toeicAttemptService;
    private VocabularyCommandService vocabularyCommandService;
    private AiAdminService aiAdminService;
    private AppUserProvisioningService provisioningService;
    private LearnerProfileService learnerProfileService;
    private OnboardingService onboardingService;
    private LexiconSearchService lexiconSearchService;

    private MockMvc lessonMvc;
    private MockMvc practiceMvc;
    private MockMvc grammarMvc;
    private MockMvc toeicMvc;
    private MockMvc vocabularyMvc;
    private MockMvc aiMvc;
    private MockMvc learnerMvc;
    private MockMvc lexiconMvc;
    private MockMvc jobMvc;

    private static final Jwt MOCK_JWT = Jwt.withTokenValue("mock.jwt.token")
        .header("alg", "none")
        .claim("sub", "keycloak-user-1")
        .claim("email", "test@lyreo.com")
        .build();

    @BeforeEach
    void setUp() {
        provisioningService = mock(AppUserProvisioningService.class);
        UUID learnerId = UUID.randomUUID();
        when(provisioningService.provision(any(), any()))
            .thenReturn(new ProvisionedUser(learnerId, "keycloak-user-1", "test@lyreo.com", Instant.now()));

        var jwtResolver = new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                return parameter.getParameterType().equals(Jwt.class);
            }

            @Override
            public Object resolveArgument(
                org.springframework.core.MethodParameter parameter,
                org.springframework.web.method.support.ModelAndViewContainer mavContainer,
                org.springframework.web.context.request.NativeWebRequest webRequest,
                org.springframework.web.bind.support.WebDataBinderFactory binderFactory
            ) {
                return MOCK_JWT;
            }
        };

        // Lesson build & preview
        lessonBuildService = mock(CreateLessonBuildService.class);
        lessonPreviewQuery = mock(LessonPreviewQuery.class);
        backgroundJobService = mock(BackgroundJobService.class);
        lessonPreviewService = new LessonPreviewService(lessonPreviewQuery, backgroundJobService);
        lessonMvc = MockMvcBuilders.standaloneSetup(new AdminLessonController(lessonBuildService, lessonPreviewService))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // Lesson practice (dictation)
        lessonPracticeService = mock(LessonPracticeService.class);
        practiceMvc = MockMvcBuilders.standaloneSetup(new LessonPracticeController(lessonPracticeService, provisioningService))
            .setCustomArgumentResolvers(jwtResolver)
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // Grammar practice
        grammarPracticeService = mock(GrammarPracticeService.class);
        grammarMvc = MockMvcBuilders.standaloneSetup(new GrammarPracticeController(grammarPracticeService, provisioningService))
            .setCustomArgumentResolvers(jwtResolver)
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // TOEIC
        toeicAttemptService = mock(ToeicAttemptService.class);
        toeicMvc = MockMvcBuilders.standaloneSetup(new ToeicAttemptController(toeicAttemptService, provisioningService))
            .setCustomArgumentResolvers(jwtResolver)
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // Vocabulary
        vocabularyCommandService = mock(VocabularyCommandService.class);
        vocabularyMvc = MockMvcBuilders.standaloneSetup(new VocabularyController(vocabularyCommandService, provisioningService))
            .setCustomArgumentResolvers(jwtResolver)
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // AI Admin
        aiAdminService = mock(AiAdminService.class);
        aiMvc = MockMvcBuilders.standaloneSetup(new AiAdminController(aiAdminService))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // Learner
        learnerProfileService = mock(LearnerProfileService.class);
        onboardingService = mock(OnboardingService.class);
        learnerMvc = MockMvcBuilders.standaloneSetup(new LearnerController(provisioningService, learnerProfileService, onboardingService))
            .setCustomArgumentResolvers(jwtResolver)
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // Lexicon
        lexiconSearchService = mock(LexiconSearchService.class);
        lexiconMvc = MockMvcBuilders.standaloneSetup(new LexiconController(lexiconSearchService))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

        // Jobs
        jobMvc = MockMvcBuilders.standaloneSetup(new JobController(backgroundJobService))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void lessonBuildReturns202WithLocationAndOmitsInternalPlan() throws Exception {
        UUID lessonId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(lessonBuildService.create(any(), any(), any(), any()))
            .thenReturn(new CreateLessonBuildService.BuildAccepted(
                lessonId, jobId, new LessonBuildPlan(LessonSourceType.TEXT, List.of(), null)
            ));

        String requestBody = """
            {
                "title": "Business English Negotiation",
                "sourceType": "TEXT",
                "sourceText": "Today we discuss the terms of agreement."
            }
            """;

        lessonMvc.perform(post("/api/v1/admin/lessons/build")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/api/v1/jobs/" + jobId))
            .andExpect(jsonPath("$.lessonId").value(lessonId.toString()))
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.plan").doesNotExist());
    }

    @Test
    void lessonPreviewReturnsExactPublicContract() throws Exception {
        UUID lessonId = UUID.randomUUID();
        UUID sentenceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-11T12:00:00Z");

        LessonPreviewView previewView = new LessonPreviewView(
            lessonId,
            "Preview Lesson",
            "TEXT",
            "Sample source text",
            "Ref-1",
            "audio/canonical.mp3",
            "READY",
            now,
            now,
            List.of(new LessonPreviewView.SentenceView(
                sentenceId,
                1,
                "Sentence text",
                100,
                500,
                "audio/clip.mp3",
                List.of(new LessonPreviewView.WordView(1, "Sentence", 100, 300)),
                List.of(new LessonPreviewView.AnnotationView("TRANSLATION", "Translation text", "ai", "openai", "gpt-4o", "VERIFIED", now))
            )),
            List.of(new LessonPreviewView.ActivityView(UUID.randomUUID(), "DICTATION", 1, true, "{}")),
            List.of(new LessonPreviewView.BuildJobView(jobId, now, null, null, null, null, null))
        );

        when(lessonPreviewQuery.find(lessonId)).thenReturn(Optional.of(previewView));
        when(backgroundJobService.findById(jobId)).thenReturn(Optional.of(new BackgroundJob(
            jobId, "LESSON_BUILD", "lesson", lessonId,
            BackgroundJobStatus.SUCCEEDED, 10, "DONE", 100,
            1, 3, null, null, null, null, null
        )));

        lessonMvc.perform(get("/api/v1/admin/lessons/" + lessonId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(lessonId.toString()))
            .andExpect(jsonPath("$.title").value("Preview Lesson"))
            .andExpect(jsonPath("$.source_type").value("TEXT"))
            .andExpect(jsonPath("$.source_text").value("Sample source text"))
            .andExpect(jsonPath("$.canonical_audio_object_key").value("audio/canonical.mp3"))
            .andExpect(jsonPath("$.sentences[0].id").value(sentenceId.toString()))
            .andExpect(jsonPath("$.sentences[0].audio_start_ms").value(100))
            .andExpect(jsonPath("$.sentences[0].words[0].surface_text").value("Sentence"))
            .andExpect(jsonPath("$.activities[0].activity_type").value("DICTATION"))
            .andExpect(jsonPath("$.buildJobs[0].job_id").value(jobId.toString()))
            .andExpect(jsonPath("$.buildJobs[0].status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.buildJobs[0].current_step").value("DONE"))
            .andExpect(jsonPath("$.buildJobs[0].progress_percent").value(100));
    }

    @Test
    void lessonPreviewReturnsStoredErrorMessageWhenJobFailed() throws Exception {
        UUID lessonId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-11T12:00:00Z");

        LessonPreviewView previewView = new LessonPreviewView(
            lessonId, "Failed Lesson", "TEXT", "Source", "Ref", null, "BUILD_FAILED", now, now,
            List.of(), List.of(),
            List.of(new LessonPreviewView.BuildJobView(jobId, now, null, null, null, null, null))
        );

        when(lessonPreviewQuery.find(lessonId)).thenReturn(Optional.of(previewView));
        when(backgroundJobService.findById(jobId)).thenReturn(Optional.of(new BackgroundJob(
            jobId, "LESSON_BUILD", "lesson", lessonId,
            BackgroundJobStatus.FAILED, 10, "GENERATE_TEXT", 30,
            3, 3, null, null, null, null, "AI provider rate limit exceeded: 429 Too Many Requests"
        )));

        lessonMvc.perform(get("/api/v1/admin/lessons/" + lessonId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(lessonId.toString()))
            .andExpect(jsonPath("$.buildJobs[0].job_id").value(jobId.toString()))
            .andExpect(jsonPath("$.buildJobs[0].status").value("FAILED"))
            .andExpect(jsonPath("$.buildJobs[0].current_step").value("GENERATE_TEXT"))
            .andExpect(jsonPath("$.buildJobs[0].error_message").value("AI provider rate limit exceeded: 429 Too Many Requests"));
    }

    @Test
    void dictationAttemptReturnsExactPublicContract() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(lessonPracticeService.submitDictation(any(), any(), any(), any(), eq("sample answer")))
            .thenReturn(new LessonPracticeService.DictationAttemptResult(
                attemptId, 85, "sample expected text", 3, 5, false, false
            ));

        UUID lessonId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID sentenceId = UUID.randomUUID();

        String body = """
            {
                "sentenceId": "%s",
                "answer": "sample answer"
            }
            """.formatted(sentenceId);

        practiceMvc.perform(post("/api/v1/lessons/%s/activities/%s/dictation/attempts".formatted(lessonId, activityId))
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(MOCK_JWT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
            .andExpect(jsonPath("$.serverScore").value(85))
            .andExpect(jsonPath("$.expectedText").value("sample expected text"))
            .andExpect(jsonPath("$.completedItems").value(3))
            .andExpect(jsonPath("$.totalItems").value(5))
            .andExpect(jsonPath("$.activityCompleted").value(false))
            .andExpect(jsonPath("$.lessonCompleted").value(false));
    }

    @Test
    void grammarQuestionsReturnsExactPublicFields() throws Exception {
        UUID qId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID subtopicId = UUID.randomUUID();
        when(grammarPracticeService.practice(any(), any()))
            .thenReturn(List.of(new GrammarPracticeService.QuestionView(
                qId,
                "Choose the correct modal verb",
                List.of(new GrammarQuestion.Option("A", "must"), new GrammarQuestion.Option("B", "ought")),
                2,
                topicId,
                subtopicId
            )));

        grammarMvc.perform(get("/api/v1/grammar/questions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(qId.toString()))
            .andExpect(jsonPath("$[0].questionText").value("Choose the correct modal verb"))
            .andExpect(jsonPath("$[0].options[0].key").value("A"))
            .andExpect(jsonPath("$[0].options[0].text").value("must"))
            .andExpect(jsonPath("$[0].options[1].key").value("B"))
            .andExpect(jsonPath("$[0].options[1].text").value("ought"))
            .andExpect(jsonPath("$[0].difficultyLevel").value(2))
            .andExpect(jsonPath("$[0].topicId").value(topicId.toString()))
            .andExpect(jsonPath("$[0].subtopicId").value(subtopicId.toString()));
    }

    @Test
    void grammarAttemptReturnsExactPublicFields() throws Exception {
        UUID attemptId = UUID.randomUUID();
        UUID qId = UUID.randomUUID();
        when(grammarPracticeService.submit(any(), eq(qId), eq("A")))
            .thenReturn(new GrammarPracticeService.SubmitResult(
                attemptId, qId, true, "A",
                "Giai thich chi tiet", "Dich cau", "Dich dap an", "Tu vung note",
                GrammarQuestion.ExplanationPolicy.SOURCE
            ));

        grammarMvc.perform(post("/api/v1/grammar/questions/%s/attempts".formatted(qId))
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(MOCK_JWT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\": \"A\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
            .andExpect(jsonPath("$.questionId").value(qId.toString()))
            .andExpect(jsonPath("$.correct").value(true))
            .andExpect(jsonPath("$.correctAnswer").value("A"))
            .andExpect(jsonPath("$.explanationVi").value("Giai thich chi tiet"))
            .andExpect(jsonPath("$.translationVi").value("Dich cau"))
            .andExpect(jsonPath("$.answerTranslationVi").value("Dich dap an"))
            .andExpect(jsonPath("$.vocabularyNote").value("Tu vung note"))
            .andExpect(jsonPath("$.explanationPolicy").value("SOURCE"));
    }

    @Test
    void toeicSubmitReturnsAttemptIdAndScoreWithoutRedundantCompletedField() throws Exception {
        UUID attemptId = UUID.randomUUID();
        UUID testId = UUID.randomUUID();
        when(toeicAttemptService.submit(any(), eq(testId), any(), any()))
            .thenReturn(new ToeicAttemptService.SubmitResult(
                attemptId,
                new com.lyreo.toeic.application.ToeicAttemptRepository.ScoreSummary(80, 100, 75, 100, 420, 390)
            ));

        toeicMvc.perform(post("/api/v1/toeic/tests/%s/attempts".formatted(testId))
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(MOCK_JWT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\": \"FULL_TEST\", \"answers\": {}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
            .andExpect(jsonPath("$.score.listeningCorrect").value(80))
            .andExpect(jsonPath("$.score.listeningTotal").value(100))
            .andExpect(jsonPath("$.score.readingCorrect").value(75))
            .andExpect(jsonPath("$.score.readingTotal").value(100))
            .andExpect(jsonPath("$.score.listeningScaledScore").value(420))
            .andExpect(jsonPath("$.score.readingScaledScore").value(390))
            .andExpect(jsonPath("$.completed").doesNotExist());
    }

    @Test
    void vocabularyDueAndReviewReturnExactContract() throws Exception {
        UUID cardId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();
        UUID lexiconId = UUID.randomUUID();
        Instant nextReview = Instant.parse("2026-09-12T10:00:00Z");

        when(vocabularyCommandService.due(any(), anyInt()))
            .thenReturn(List.of(new VocabularyCard(
                cardId, learnerId, lexiconId, "LESSON", UUID.randomUUID(),
                nextReview, 2.5, 3.0, 0, 1
            )));

        vocabularyMvc.perform(get("/api/v1/vocabulary/due")
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(MOCK_JWT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(cardId.toString()))
            .andExpect(jsonPath("$[0].learnerId").value(learnerId.toString()))
            .andExpect(jsonPath("$[0].lexiconEntryId").value(lexiconId.toString()))
            .andExpect(jsonPath("$[0].sourceContextType").value("LESSON"))
            .andExpect(jsonPath("$[0].nextReviewAt").value("2026-09-12T10:00:00Z"))
            .andExpect(jsonPath("$[0].stability").value(2.5))
            .andExpect(jsonPath("$[0].difficulty").value(3.0))
            .andExpect(jsonPath("$[0].lapseCount").value(0))
            .andExpect(jsonPath("$[0].reviewCount").value(1));

        when(vocabularyCommandService.review(any(), eq(cardId), any()))
            .thenReturn(new SpacedRepetitionScheduler.ScheduleResult(nextReview, 2.7, 2.9));

        vocabularyMvc.perform(post("/api/v1/vocabulary/cards/%s/review".formatted(cardId))
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(MOCK_JWT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\": \"GOOD\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cardId").value(cardId.toString()))
            .andExpect(jsonPath("$.nextReviewAt").value("2026-09-12T10:00:00Z"))
            .andExpect(jsonPath("$.stability").value(2.7))
            .andExpect(jsonPath("$.difficulty").value(2.9));
    }

    @Test
    void aiProvidersAndRoutesRetainIntendedWireKeys() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(aiAdminService.providers()).thenReturn(List.of(
            new ProviderSummary(
                providerId, "OPENAI", "OpenAI Official", "https://api.openai.com/v1",
                true, "HEALTHY", "4821", true
            )
        ));

        aiMvc.perform(get("/api/v1/admin/ai/providers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(providerId.toString()))
            .andExpect(jsonPath("$[0].code").value("OPENAI"))
            .andExpect(jsonPath("$[0].display_name").value("OpenAI Official"))
            .andExpect(jsonPath("$[0].base_url").value("https://api.openai.com/v1"))
            .andExpect(jsonPath("$[0].enabled").value(true))
            .andExpect(jsonPath("$[0].connection_status").value("HEALTHY"))
            .andExpect(jsonPath("$[0].key_last4").value("4821"))
            .andExpect(jsonPath("$[0].configured").value(true));

        UUID routeId = UUID.randomUUID();
        when(aiAdminService.routes()).thenReturn(List.of(
            new RouteSummary(
                routeId, "LESSON_TEXT_GENERATION", "OPENAI", "gpt-4o", 1, false, true
            )
        ));

        aiMvc.perform(get("/api/v1/admin/ai/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(routeId.toString()))
            .andExpect(jsonPath("$[0].capability").value("LESSON_TEXT_GENERATION"))
            .andExpect(jsonPath("$[0].provider").value("OPENAI"))
            .andExpect(jsonPath("$[0].model").value("gpt-4o"))
            .andExpect(jsonPath("$[0].priority").value(1))
            .andExpect(jsonPath("$[0].is_fallback").value(false))
            .andExpect(jsonPath("$[0].enabled").value(true))
            .andExpect(jsonPath("$[0].config_json").doesNotExist());
    }

    @Test
    void learnerProfileAndPreferencesReturnExactWireShape() throws Exception {
        UUID learnerId = UUID.randomUUID();
        when(provisioningService.provision(any(), any()))
            .thenReturn(new ProvisionedUser(learnerId, "keycloak-user-1", "test@lyreo.com", Instant.now()));

        LearnerPreferences prefs = LearnerPreferences.defaults();
        when(learnerProfileService.findByLearnerId(learnerId)).thenReturn(Optional.of(
            new LearnerProfile(learnerId, "Jane Doe", "B1", "Career", 20, "SPEAKING", prefs)
        ));
        when(learnerProfileService.findPreferences(learnerId)).thenReturn(prefs);

        learnerMvc.perform(get("/api/v1/learner/profile")
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(MOCK_JWT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(learnerId.toString()))
            .andExpect(jsonPath("$.onboarded").value(true))
            .andExpect(jsonPath("$.profile.learnerId").value(learnerId.toString()))
            .andExpect(jsonPath("$.profile.displayName").value("Jane Doe"))
            .andExpect(jsonPath("$.profile.currentLevel").value("B1"))
            .andExpect(jsonPath("$.profile.goal").value("Career"))
            .andExpect(jsonPath("$.profile.dailyMinutes").value(20))
            .andExpect(jsonPath("$.profile.focusArea").value("SPEAKING"))
            .andExpect(jsonPath("$.profile.preferences.preferredAccent").value("US"))
            .andExpect(jsonPath("$.profile.preferences.defaultPlaybackSpeed").value(1.0));

        learnerMvc.perform(get("/api/v1/learner/preferences")
                .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(MOCK_JWT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredAccent").value("US"))
            .andExpect(jsonPath("$.translation").value("AFTER_ATTEMPT"))
            .andExpect(jsonPath("$.sentenceIpa").value("TAP_TO_SHOW"))
            .andExpect(jsonPath("$.vocabularyNotes").value("AFTER_ATTEMPT"))
            .andExpect(jsonPath("$.grammarNotes").value("AFTER_ATTEMPT"))
            .andExpect(jsonPath("$.thoughtGroups").value(true))
            .andExpect(jsonPath("$.karaokeHighlighting").value(true))
            .andExpect(jsonPath("$.properNounHints").value(true))
            .andExpect(jsonPath("$.defaultPlaybackSpeed").value(1.0));
    }

    @Test
    void lexiconDetailAndSearchReturnExactWireShape() throws Exception {
        UUID entryId = UUID.randomUUID();
        UUID senseId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        LexiconEntry entry = new LexiconEntry(
            entryId,
            "resilient",
            "resilient",
            LexiconEntry.EntryType.WORD,
            "en",
            List.of(new LexiconEntry.LexiconSense(
                senseId,
                "adjective",
                "Able to withstand or recover quickly from difficult conditions.",
                "kiên cường, có khả năng phục hồi",
                LexiconEntry.TranslationStatus.VERIFIED,
                sourceId
            )),
            List.of(new LexiconEntry.Pronunciation(
                "US",
                "/rɪˈzɪl.jənt/",
                "https://audio.example.com/resilient.mp3",
                "audio/lexicon/resilient_us.mp3",
                sourceId
            ))
        );

        when(lexiconSearchService.findById(entryId)).thenReturn(Optional.of(entry));
        when(lexiconSearchService.search("resilient", 20)).thenReturn(List.of(entry));

        lexiconMvc.perform(get("/api/v1/lexicon/" + entryId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(entryId.toString()))
            .andExpect(jsonPath("$.canonicalForm").value("resilient"))
            .andExpect(jsonPath("$.normalizedForm").value("resilient"))
            .andExpect(jsonPath("$.type").value("WORD"))
            .andExpect(jsonPath("$.language").value("en"))
            .andExpect(jsonPath("$.senses[0].id").value(senseId.toString()))
            .andExpect(jsonPath("$.senses[0].partOfSpeech").value("adjective"))
            .andExpect(jsonPath("$.senses[0].definitionEn").value("Able to withstand or recover quickly from difficult conditions."))
            .andExpect(jsonPath("$.senses[0].translationVi").value("kiên cường, có khả năng phục hồi"))
            .andExpect(jsonPath("$.senses[0].translationStatus").value("VERIFIED"))
            .andExpect(jsonPath("$.senses[0].sourceId").value(sourceId.toString()))
            .andExpect(jsonPath("$.pronunciations[0].accent").value("US"))
            .andExpect(jsonPath("$.pronunciations[0].ipa").value("/rɪˈzɪl.jənt/"))
            .andExpect(jsonPath("$.pronunciations[0].cachedAudioObjectKey").value("audio/lexicon/resilient_us.mp3"));

        lexiconMvc.perform(get("/api/v1/lexicon/search").param("q", "resilient"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(entryId.toString()))
            .andExpect(jsonPath("$[0].canonicalForm").value("resilient"));
    }

    @Test
    void standaloneJobReturnsExactWireShape() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(backgroundJobService.findById(jobId)).thenReturn(Optional.of(new BackgroundJob(
            jobId, "LESSON_BUILD", "lesson", UUID.randomUUID(),
            BackgroundJobStatus.RUNNING, 10, "GENERATE_TEXT", 45,
            1, 3, null, "worker-1", Instant.now().plusSeconds(60), null, null
        )));

        jobMvc.perform(get("/api/v1/jobs/" + jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(jobId.toString()))
            .andExpect(jsonPath("$.jobType").value("LESSON_BUILD"))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.currentStep").value("GENERATE_TEXT"))
            .andExpect(jsonPath("$.progressPercent").value(45))
            .andExpect(jsonPath("$.attemptCount").value(1))
            .andExpect(jsonPath("$.maxAttempts").value(3));
    }
}
