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
        draft.id = UUID.randomUUID(); draft.title = "Review"; draft.revision = 3;
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
                .andExpect(jsonPath("$.audioObjectKey").doesNotExist())
                .andExpect(jsonPath("$.activeJobId").doesNotExist())
                .andExpect(jsonPath("$.sentences[0].coarseStartMs").doesNotExist())
                .andExpect(jsonPath("$.sentences[0].sourceWords").doesNotExist());
        }
    }
}
