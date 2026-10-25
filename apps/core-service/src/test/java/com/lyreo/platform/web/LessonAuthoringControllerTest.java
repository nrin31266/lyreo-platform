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
    @Test void readAndCheckpointResponsesExposeReviewContentWithoutStorageOrWorkflowState() throws Exception {
        var service = mock(LessonAuthoringService.class);
        var draft = new AuthoringLesson();
        draft.id = UUID.randomUUID(); draft.title = "Review"; draft.revision = 3;
        draft.audioObjectKey = "private/source.wav"; draft.activeJobId = UUID.randomUUID();
        var sentence = new AuthoringSentence(UUID.randomUUID(), "Hello world.", "LEARN");
        sentence.coarseStartMs = 0L; sentence.coarseEndMs = 1000L;
        draft.sentences = List.of(sentence);
        when(service.get(draft.id)).thenReturn(draft);
        when(service.approve(draft.id, 2)).thenReturn(draft);
        var mvc = MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service)).build();
        for (var request : List.of(get("/api/v1/admin/lesson-authoring/" + draft.id),
            post("/api/v1/admin/lesson-authoring/" + draft.id + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))) {
            mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(draft.id.toString()))
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.sentences[0].text").value("Hello world."))
                .andExpect(jsonPath("$.audioObjectKey").doesNotExist())
                .andExpect(jsonPath("$.activeJobId").doesNotExist())
                .andExpect(jsonPath("$.sentences[0].coarseStartMs").doesNotExist());
        }
    }
}
