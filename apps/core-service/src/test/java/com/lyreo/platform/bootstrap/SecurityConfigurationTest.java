package com.lyreo.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SecurityConfigurationTest {
    @Test
    void permitsPronunciationIdempotencyHeaderForConfiguredBrowserOrigins() {
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/admin/lesson-authoring/id/sentences/id/pronunciation-attempts");
        var cors = new SecurityConfiguration().corsConfigurationSource("http://localhost:5173").getCorsConfiguration(request);

        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("https://unlisted.example")).isNull();
        assertThat(cors.checkHeaders(List.of("authorization", "idempotency-key")))
            .containsExactly("authorization", "idempotency-key");
    }
}
