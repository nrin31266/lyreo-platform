package com.lyreo.vocabulary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.identity.application.ProvisionedUser;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.api.VocabularyStudyController;
import com.lyreo.vocabulary.application.VocabularyStudyService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
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
}
