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
import com.lyreo.lesson.api.AdminLessonController;
import com.lyreo.lesson.api.LessonPracticeController;
import com.lyreo.lesson.application.CreateLessonBuildService;
import com.lyreo.lesson.application.LessonPracticeService;
import com.lyreo.lesson.application.LessonPreviewQuery;
import com.lyreo.lesson.domain.LessonBuildPlan;
import com.lyreo.lesson.domain.LessonSourceType;
import com.lyreo.platform.observability.CorrelationIdFilter;
import com.lyreo.toeic.api.ToeicAttemptController;
import com.lyreo.toeic.application.ToeicAttemptService;
import com.lyreo.vocabulary.api.VocabularyController;
import com.lyreo.vocabulary.application.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Instant;
import java.util.List;
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
    private LessonPracticeService lessonPracticeService;
    private GrammarPracticeService grammarPracticeService;
    private ToeicAttemptService toeicAttemptService;
    private VocabularyCommandService vocabularyCommandService;
    private AiAdminService aiAdminService;
    private AppUserProvisioningService provisioningService;

    private MockMvc lessonMvc;
    private MockMvc practiceMvc;
    private MockMvc grammarMvc;
    private MockMvc toeicMvc;
    private MockMvc vocabularyMvc;
    private MockMvc aiMvc;

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

        // Lesson build
        lessonBuildService = mock(CreateLessonBuildService.class);
        lessonMvc = MockMvcBuilders.standaloneSetup(new AdminLessonController(lessonBuildService, mock(LessonPreviewQuery.class)))
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
}
