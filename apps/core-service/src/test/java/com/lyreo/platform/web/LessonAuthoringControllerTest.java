package com.lyreo.platform.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lyreo.lesson.api.LessonAuthoringController;
import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LessonAuthoringControllerTest {
    @Test void batchSaveRejectsUnknownVoicesBeforeWriting() throws Exception {
        var service = mock(LessonAuthoringService.class);
        var media = mock(com.lyreo.lesson.application.port.AuthoringMediaPort.class);
        when(media.voices()).thenReturn(new com.lyreo.lesson.application.port.AuthoringMediaPort.Voices(
            "af_heart", List.of(new com.lyreo.lesson.application.port.AuthoringMediaPort.Voice("af_heart", "US", "Heart"))));
        var catalog = mock(com.lyreo.lesson.application.port.LessonCatalogPort.class);
        var mvc = MockMvcBuilders.standaloneSetup(
            new LessonAuthoringController(service, catalog),
            new com.lyreo.lesson.api.LessonMediaController(media, service, catalog,
                mock(com.lyreo.lesson.application.port.VoiceSamplesPort.class)))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(put("/api/v1/admin/lesson-authoring/" + UUID.randomUUID() + "/changes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"changes\":{\"revision\":0,\"voice\":\"bf_unknown\",\"sentences\":[]}}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service, catalog);
    }

    @Test void publicationRecordsTheTokenSubjectWhenThePrincipalNameIsAnEmail() throws Exception {
        var service=mock(LessonAuthoringService.class);
        var draft=new AuthoringLesson();draft.id=UUID.randomUUID();
        UUID subject=UUID.randomUUID();
        var token=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg","none")
            .subject(subject.toString()).claim("preferred_username","admin@local.test").build();
        var authentication=new com.lyreo.platform.bootstrap.KeycloakJwtAuthenticationConverter().convert(token);
        when(service.publish(draft.id,7,subject)).thenReturn(draft);
        var mvc=MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,mock(com.lyreo.lesson.application.port.LessonCatalogPort.class))).build();
        mvc.perform(post("/api/v1/admin/lesson-authoring/"+draft.id+"/publish").principal(authentication)
            .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
            .andExpect(status().isOk());
        verify(service).publish(draft.id,7,subject);
    }
    @Test void contentEndpointsKeepRevisionChecksAndValidateProfileEnums() throws Exception {
        var service=mock(LessonAuthoringService.class);
        var draft=new AuthoringLesson();draft.id=UUID.randomUUID();draft.endMs=1000;
        when(service.saveProfile(draft.id,7,com.lyreo.lesson.domain.authoring.AuthoringProfile.Cefr.B1,
            com.lyreo.lesson.domain.authoring.AuthoringProfile.ContentType.STORY)).thenReturn(draft);
        var mvc=MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,mock(com.lyreo.lesson.application.port.LessonCatalogPort.class))).build();
        String base="/api/v1/admin/lesson-authoring/"+draft.id;
        mvc.perform(put(base+"/content-profile").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7,\"cefr\":\"B1\",\"contentType\":\"STORY\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.profile.cefr").value("UNKNOWN"));
        verify(service).saveProfile(draft.id,7,com.lyreo.lesson.domain.authoring.AuthoringProfile.Cefr.B1,com.lyreo.lesson.domain.authoring.AuthoringProfile.ContentType.STORY);
        mvc.perform(put(base+"/content-profile").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7,\"cefr\":\"BEGINNER\",\"contentType\":\"CUSTOM\"}"))
            .andExpect(status().isBadRequest());
        when(service.generateContent(draft.id,7)).thenReturn(new LessonAuthoringService.Accepted(draft.id,UUID.randomUUID()));
        mvc.perform(post(base+"/content").contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
            .andExpect(status().isAccepted()).andExpect(header().exists("Location"));
        verify(service).generateContent(draft.id,7);
    }
    @Test void textCreationCanEnqueueTheChainBeforeReturningItsCurrentRevision() throws Exception {
        var service=mock(LessonAuthoringService.class);
        var media=mock(com.lyreo.lesson.application.port.AuthoringMediaPort.class);
        var catalog=mock(com.lyreo.lesson.application.port.LessonCatalogPort.class);
        when(media.voices()).thenReturn(new com.lyreo.lesson.application.port.AuthoringMediaPort.Voices("af_heart",List.of(new com.lyreo.lesson.application.port.AuthoringMediaPort.Voice("af_heart","US","Heart"))));
        var draft=new AuthoringLesson();draft.id=UUID.randomUUID();
        when(service.createText(anyString(),anyString(),anyString(),any())).thenReturn(draft);
        when(service.get(draft.id)).thenReturn(draft);
        var mvc=MockMvcBuilders.standaloneSetup(new com.lyreo.lesson.api.LessonMediaController(media,service,catalog,mock(com.lyreo.lesson.application.port.VoiceSamplesPort.class))).build();
        mvc.perform(post("/api/v1/admin/lesson-authoring/text-drafts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Test\",\"text\":\"We learn.\",\"voice\":\"af_heart\",\"startProcessing\":true}"))
            .andExpect(status().isCreated());
        var order=inOrder(service,catalog);order.verify(service).createText("Test","We learn.","af_heart",null);
        order.verify(catalog).updateMetadata(draft.id,null,"",null,List.of());
        order.verify(service).synthesize(draft.id,0);order.verify(service).get(draft.id);
    }
    @Test void repeatedSuffixIsAReadTimeReviewHintAndNeverChangesTranscriptText() throws Exception {
        var service=mock(LessonAuthoringService.class);
        var draft=new AuthoringLesson();draft.id=UUID.randomUUID();draft.endMs=5000;
        var first=new AuthoringSentence(UUID.randomUUID(),"They sting him on his elbow.","LEARN");
        var next=new AuthoringSentence(UUID.randomUUID(),"his elbow.","LEARN");
        next.hidden=true;
        draft.sentences=List.of(first,next);when(service.get(draft.id)).thenReturn(draft);
        var mvc=MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,
            mock(com.lyreo.lesson.application.port.LessonCatalogPort.class))).build();
        mvc.perform(get("/api/v1/admin/lesson-authoring/"+draft.id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.sentences[1].issues").value(org.hamcrest.Matchers.hasItem("POSSIBLE_REPEAT")))
            .andExpect(jsonPath("$.sentences[1].text").value("his elbow."));
        mvc.perform(get("/api/v1/admin/lesson-authoring/"+draft.id)).andExpect(jsonPath("$.sentences[1].hidden").value(true))
            .andExpect(jsonPath("$.sentences[0].hidden").value(false));
        org.assertj.core.api.Assertions.assertThat(next.issues).isEmpty();
        verify(service,times(2)).get(draft.id);verifyNoMoreInteractions(service);
    }
    @Test void withdrawnAuthoringWritesAreNotMapped() throws Exception {
        var service=mock(LessonAuthoringService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service,mock(com.lyreo.lesson.application.port.LessonCatalogPort.class))).build();
        String base="/api/v1/admin/lesson-authoring/"+UUID.randomUUID();
        for(String path:List.of("generate","ipa","replace-generated"))
            mvc.perform(post(base+"/"+path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        for(String path:List.of("options","candidates"))
            mvc.perform(put(base+"/"+path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void youtubeEndpointsExposeCaptionAvailabilityAndAcceptExplicitWhisperX() throws Exception {
        var service = mock(LessonAuthoringService.class);
        var catalog = mock(com.lyreo.lesson.application.port.LessonCatalogPort.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LessonAuthoringController(service, catalog)).build();
        String base = "/api/v1/admin/lesson-authoring";
        String url = "https://youtu.be/abcdefghijk";
        UUID id = UUID.randomUUID();
        var accepted = new LessonAuthoringService.Accepted(id, UUID.randomUUID());
        when(service.inspect(url)).thenReturn(new LessonAuthoringService.Inspect(
            "abcdefghijk", "Reference", 10000, true, List.of(), null, new AuthoringSourcePort.CaptionTrack("en", true)));
        when(service.create(null, url, 0, 1000, null, true, null)).thenReturn(accepted);
        when(service.prepare(id, 7, true)).thenReturn(accepted);
        mvc.perform(post(base + "/inspect").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceReference\":\"" + url + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.englishCaptions.language").value("en"))
            .andExpect(jsonPath("$.englishCaptions.automatic").value(true))
            .andExpect(jsonPath("$.englishCaptions.transcriptSource").doesNotExist());
        mvc.perform(post(base + "/drafts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceReference\":\"" + url + "\",\"startMs\":0,\"endMs\":1000,\"preferEnglishCaptions\":true}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(accepted.jobId().toString()));
        mvc.perform(post(base + "/" + id + "/prepare").contentType(MediaType.APPLICATION_JSON)
            .content("{\"revision\":7,\"useWhisperX\":true}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(accepted.jobId().toString()));
    }

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
        doThrow(new com.lyreo.lesson.application.build.SourceMaterializationException(
            "YOUTUBE_MEDIA_TIMEOUT", "credential=secret provider details", true))
            .when(service).create(isNull(), anyString(), eq(0L), eq(1000L), isNull(), eq(false), isNull());
        mvc.perform(post("/api/v1/admin/lesson-authoring/drafts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceReference\":\"https://youtu.be/abcdefghijk\",\"startMs\":0,\"endMs\":1000}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("YOUTUBE_MEDIA_TIMEOUT"))
            .andExpect(jsonPath("$.detail").value("YouTube source is unavailable. Check the source and media tools, then retry."));
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
        draft.timingEvidence = java.util.Map.of("transcriptSource", "YOUTUBE_EN_MANUAL");
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
                .andExpect(jsonPath("$.transcriptSource").value("YOUTUBE_EN_MANUAL"))
                .andExpect(jsonPath("$.timingEvidence").doesNotExist())
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
