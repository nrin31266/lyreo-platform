package com.lyreo.vocabulary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.identity.application.ProvisionedUser;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.api.VocabularyStudyController;
import com.lyreo.vocabulary.application.VocabularyStudyService;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.domain.PracticeMode;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class VocabularyStudyControllerTest {

    private static final Jwt MOCK_JWT = Jwt.withTokenValue("mock.jwt")
        .header("alg", "none")
        .claim("sub", "learner-1")
        .claim("email", "learner@lyreo.test")
        .build();

    private UUID learnerId;
    private VocabularyStudyService studyService;
    private AssetStore assets;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        learnerId = UUID.randomUUID();
        AppUserProvisioningService users = mock(AppUserProvisioningService.class);
        when(users.provision(any(), any())).thenReturn(
            new ProvisionedUser(learnerId, "learner-1", "learner@lyreo.test", Instant.now())
        );
        studyService = mock(VocabularyStudyService.class);
        assets = mock(AssetStore.class);

        HandlerMethodArgumentResolver jwtResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(Jwt.class);
            }

            @Override
            public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory
            ) {
                return MOCK_JWT;
            }
        };

        mvc = MockMvcBuilders.standaloneSetup(new VocabularyStudyController(studyService, users, assets))
            .setCustomArgumentResolvers(jwtResolver)
            .build();
    }

    @Test
    void streamsEntryMediaWithSecurityHeadersAndByteRanges() throws Exception {
        UUID entryId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);

        when(studyService.authorizedMedia(learnerId, entryId, assetId))
            .thenReturn(new AssetStore.Asset(assetId, "audio/mpeg", payload.length, "word.mp3", false));
        when(assets.bytes(assetId)).thenReturn(payload);

        mvc.perform(get("/api/v1/vocabulary/entries/{entryId}/media/{assetId}", entryId, assetId))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(content().string("0123456789"));

        mvc.perform(get("/api/v1/vocabulary/entries/{entryId}/media/{assetId}", entryId, assetId)
                .header("Range", "bytes=2-5"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 2-5/10"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().string("2345"));

        mvc.perform(get("/api/v1/vocabulary/entries/{entryId}/media/{assetId}", entryId, assetId)
                .header("Range", "bytes=20-30"))
            .andExpect(status().isRequestedRangeNotSatisfiable())
            .andExpect(header().string("Content-Range", "bytes */10"));
    }

    @Test
    void supportsFlashcardsGuessHintCheckAndPackSessionEndpoints() throws Exception {
        UUID packId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-28T00:00:00Z");

        FlashcardDeckResponse deck = new FlashcardDeckResponse(
            packId,
            unitId,
            FlashcardScope.DUE_AND_NEW,
            new FlashcardCounts(1, 1, 1, 1, 0, 1, 0, 0),
            List.of()
        );
        when(studyService.getFlashcardDeck(learnerId, unitId, null, FlashcardScope.DUE_AND_NEW, 30))
            .thenReturn(deck);

        mvc.perform(get("/api/v1/vocabulary/flashcards")
                .param("unitId", unitId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("DUE_AND_NEW"))
            .andExpect(jsonPath("$.counts.newCount").value(1));

        FlashcardReviewResponse reviewRes = new FlashcardReviewResponse(
            entryId, unitId, packId, "LEARNING", now.plusSeconds(86400), 2.0, 5.0, 1, 0, Map.of()
        );
        when(studyService.reviewFlashcard(learnerId, entryId, SpacedRepetitionScheduler.Rating.GOOD, 0, "rev-1"))
            .thenReturn(reviewRes);

        mvc.perform(post("/api/v1/vocabulary/entries/{entryId}/flashcard-review", entryId)
                .header("Idempotency-Key", "rev-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":\"GOOD\",\"expectedReviewCount\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.srsState").value("LEARNING"))
            .andExpect(jsonPath("$.reviewCount").value(1));

        SessionView packSession = new SessionView(
            sessionId, packId, null, "Business", PracticeMode.MIXED, "OPEN", 1L,
            now, now.plusSeconds(3600), null, List.of(), Map.of(), null
        );
        when(studyService.startPackSession(
                eq(learnerId), eq(packId), eq(PracticeMode.MIXED),
                eq(List.of(PracticeMode.CHOOSE_MEANING, PracticeMode.GUESS_WORD)), eq(15), eq(null), eq("pack-1")))
            .thenReturn(packSession);

        mvc.perform(post("/api/v1/vocabulary/packs/{packId}/sessions", packId)
                .header("Idempotency-Key", "pack-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"MIXED\",\"modes\":[\"CHOOSE_MEANING\",\"GUESS_WORD\"],\"limit\":15}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/vocabulary/sessions/" + sessionId))
            .andExpect(jsonPath("$.mode").value("MIXED"));

        when(studyService.startSession(
                eq(learnerId), eq(unitId), eq(PracticeMode.CHOOSE_MEANING),
                eq(null), eq(null), eq(List.of(entryId)), eq("unit-slice-1")))
            .thenReturn(packSession);

        mvc.perform(post("/api/v1/vocabulary/units/{unitId}/sessions", unitId)
                .header("Idempotency-Key", "unit-slice-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CHOOSE_MEANING\",\"entryIds\":[\"" + entryId + "\"]}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/vocabulary/sessions/" + sessionId));

        HintResponse hint = new HintResponse(questionId, "j****", List.of(0), 1, 4);
        when(studyService.requestHint(learnerId, sessionId, questionId)).thenReturn(hint);

        mvc.perform(post("/api/v1/vocabulary/sessions/{id}/questions/{qid}/hint", sessionId, questionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maskedTerm").value("j****"))
            .andExpect(jsonPath("$.hintsUsed").value(1));

        CheckQuestionResponse check = new CheckQuestionResponse(
            2L, questionId, entryId, "chung", "joint", "joint", "joint", true, 1, false
        );
        when(studyService.checkQuestion(learnerId, sessionId, questionId, 1L, "joint", false))
            .thenReturn(check);

        mvc.perform(post("/api/v1/vocabulary/sessions/{id}/questions/{qid}/check", sessionId, questionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1,\"answer\":\"joint\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.correct").value(true))
            .andExpect(jsonPath("$.revision").value(2));
    }
}
