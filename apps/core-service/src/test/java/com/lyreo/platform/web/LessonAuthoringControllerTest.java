package com.lyreo.platform.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lyreo.lesson.api.LessonAuthoringController;
import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LessonAuthoringControllerTest {
    @Test void inspectTranslatesExpectedSourceFailuresToSafeServiceUnavailableProblems() throws Exception {
        var service = mock(LessonAuthoringService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,mock(com.lyreo.lesson.application.port.LessonCatalogPort.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new com.lyreo.platform.observability.CorrelationIdFilter()).build();
        for (String code : List.of("YOUTUBE_MEDIA_TOOL_DISABLED", "YOUTUBE_MEDIA_TOOL_UNAVAILABLE",
            "YOUTUBE_MEDIA_TIMEOUT", "YOUTUBE_MEDIA_FAILED", "YOUTUBE_MEDIA_INTERRUPTED")) {
            doThrow(new com.lyreo.lesson.application.build.SourceMaterializationException(
                code, "credential=secret provider details", true)).when(service).inspect(anyString());
            mvc.perform(post("/api/v1/admin/lesson-authoring/inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceReference\":\"https://youtu.be/abcdefghijk\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.detail").value("YouTube source is unavailable. Check the source and media tools, then retry."));
        }
        doThrow(new com.lyreo.contracts.errors.RequestValidationException("A valid YouTube video URL is required")).when(service).inspect(anyString());
        mvc.perform(post("/api/v1/admin/lesson-authoring/inspect")
            .contentType(MediaType.APPLICATION_JSON).content("{\"sourceReference\":\"invalid\"}"))
            .andExpect(status().isBadRequest());
        doThrow(new IllegalStateException("unexpected")).when(service).inspect(anyString());
        mvc.perform(post("/api/v1/admin/lesson-authoring/inspect")
            .contentType(MediaType.APPLICATION_JSON).content("{\"sourceReference\":\"https://youtu.be/abcdefghijk\"}"))
            .andExpect(status().isInternalServerError());
    }

    @Test void readAndCheckpointResponsesExposeReviewContentWithoutStorageOrWorkflowState() throws Exception {
        var service = mock(LessonAuthoringService.class);
        var draft = new AuthoringLesson();
        draft.id = UUID.randomUUID(); draft.title = "Review"; draft.revision = 3; draft.endMs = 5000;
        draft.audioObjectKey = "private/source.wav"; draft.activeJobId = UUID.randomUUID();
        var sentence = new AuthoringSentence(UUID.randomUUID(), "Hello world.", "LEARN");
        sentence.coarseStartMs = 0L; sentence.coarseEndMs = 1000L;
        sentence.sourceWords = List.of(new com.lyreo.lesson.domain.authoring.AuthoringText.ObservedWord("Hello",0,500));
        draft.sentences = List.of(sentence);
        when(service.get(draft.id)).thenReturn(draft);
        when(service.approve(draft.id, 2)).thenReturn(draft);
        var mvc = MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,mock(com.lyreo.lesson.application.port.LessonCatalogPort.class))).build();
        for (var request : List.of(get("/api/v1/admin/lesson-authoring/" + draft.id),
            post("/api/v1/admin/lesson-authoring/" + draft.id + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))) {
            mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(draft.id.toString()))
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.sentences[0].text").value("Hello world."))
                .andExpect(jsonPath("$.sentences[0].previewStartMs").value(0))
                .andExpect(jsonPath("$.audioObjectKey").doesNotExist())
                .andExpect(jsonPath("$.activeJobId").doesNotExist())
                .andExpect(jsonPath("$.sentences[0].coarseStartMs").doesNotExist())
                .andExpect(jsonPath("$.sentences[0].sourceWords").doesNotExist());
        }
    }
    @Test void previewUsesWordsFirstAndDoesNotInventMissingOrOutOfRangeAnchors() throws Exception {
        var service = mock(LessonAuthoringService.class);
        var draft = new AuthoringLesson();
        draft.id = UUID.randomUUID(); draft.endMs = 5000;
        var word = new AuthoringSentence(UUID.randomUUID(), "Hello", "LEARN");
        word.coarseStartMs = 0L;
        word.words = List.of(new AuthoringSentence.Word("Hello", 500, 1000, 0, 5, 0, 1));
        var missing = new AuthoringSentence(UUID.randomUUID(), "Missing", "LEARN");
        var outside = new AuthoringSentence(UUID.randomUUID(), "Outside", "LEARN");
        outside.coarseStartMs = 5000L;
        draft.sentences = List.of(word, missing, outside);
        when(service.get(draft.id)).thenReturn(draft);
        var mvc = MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,
            mock(com.lyreo.lesson.application.port.LessonCatalogPort.class))).build();
        mvc.perform(get("/api/v1/admin/lesson-authoring/" + draft.id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.sentences[0].previewStartMs").value(500))
            .andExpect(jsonPath("$.sentences[1].previewStartMs").doesNotExist())
            .andExpect(jsonPath("$.sentences[2].previewStartMs").doesNotExist());
    }

    @Test void transcriptWarningsRemainVisibleWhenWordCoverageIsInvalid() throws Exception {
        var service = mock(LessonAuthoringService.class);
        var draft = new AuthoringLesson();
        draft.id = UUID.randomUUID(); draft.endMs = 5000;
        var shortSentence = new AuthoringSentence(UUID.randomUUID(), "Yes.", "LEARN");
        var longSentence = new AuthoringSentence(UUID.randomUUID(), "word ".repeat(46), "LEARN");
        shortSentence.issues = List.of("TIMING_COVERAGE_MISMATCH");
        longSentence.issues = List.of("TIMING_COVERAGE_MISMATCH");
        draft.sentences = List.of(shortSentence, longSentence);
        when(service.get(draft.id)).thenReturn(draft);
        var mvc = MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,
            mock(com.lyreo.lesson.application.port.LessonCatalogPort.class))).build();
        mvc.perform(get("/api/v1/admin/lesson-authoring/" + draft.id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.sentences[0].issues").value(org.hamcrest.Matchers.hasItems("SHORT_SENTENCE", "TIMING_COVERAGE_MISMATCH")))
            .andExpect(jsonPath("$.sentences[1].issues").value(org.hamcrest.Matchers.hasItems("LONG_SENTENCE", "TIMING_COVERAGE_MISMATCH")));
    }

}
