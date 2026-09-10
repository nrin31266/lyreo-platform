package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.platform.bootstrap.KeycloakJwtAuthenticationConverter;
import com.lyreo.platform.observability.CorrelationIdAccessor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.net.URI;
import tools.jackson.databind.JsonNode;

class ApiSecurityContractTest {

    private final ApiProblemWriter problemWriter = new ApiProblemWriter(TestMappers.productionJsonMapper());
    private final ProblemAuthenticationEntryPoint authenticationEntryPoint =
        new ProblemAuthenticationEntryPoint(problemWriter);
    private final ProblemAccessDeniedHandler accessDeniedHandler =
        new ProblemAccessDeniedHandler(problemWriter);

    @Test
    void unauthenticatedProduces401WithWwwAuthenticateAndProblemDetail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/lessons/build");
        request.setAttribute(CorrelationIdAccessor.ATTRIBUTE, "corr-sec-401");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authenticationEntryPoint.commence(request, response, new BadCredentialsException("Token expired"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).contains("Bearer");

        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
        assertThat(body).contains("\"status\":401");
        assertThat(body).contains("\"correlationId\":\"corr-sec-401\"");
        assertThat(body).contains("\"type\":\"urn:lyreo:problem:authentication-required\"");
    }

    @Test
    void accessDeniedProduces403ProblemDetail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/lessons/build");
        request.setAttribute(CorrelationIdAccessor.ATTRIBUTE, "corr-sec-403");
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(request, response, new AccessDeniedException("Access denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).contains("Bearer");

        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":\"ACCESS_DENIED\"");
        assertThat(body).contains("\"status\":403");
        assertThat(body).contains("\"correlationId\":\"corr-sec-403\"");
        assertThat(body).contains("\"type\":\"urn:lyreo:problem:access-denied\"");
        assertThat(body).doesNotContain("\"properties\"");
    }

    @Test
    void wrongRoleReturnsProblemDetailAndBearerHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/lessons/build");
        request.setAttribute(CorrelationIdAccessor.ATTRIBUTE, "corr-sec-wrong-role");
        Jwt jwt = Jwt.withTokenValue("mock-bearer-token")
            .header("alg", "none")
            .claim("sub", "learner-123")
            .claim("scope", "read")
            .build();
        request.setUserPrincipal(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_LEARNER"))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(request, response, new AccessDeniedException("Insufficient role"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).contains("Bearer error=\"insufficient_scope\"");

        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":\"ACCESS_DENIED\"");
        assertThat(body).contains("\"status\":403");
        assertThat(body).contains("\"correlationId\":\"corr-sec-wrong-role\"");
        assertThat(body).contains("\"type\":\"urn:lyreo:problem:access-denied\"");
    }

    @Test
    void missingSubjectInJwtProduces401AuthenticationRequired() throws Exception {
        KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();
        Jwt jwtWithoutSub = Jwt.withTokenValue("mock-bearer-token-without-sub")
            .header("alg", "none")
            .claim("preferred_username", "testuser")
            .build();

        OAuth2AuthenticationException authException = null;
        try {
            converter.convert(jwtWithoutSub);
        } catch (OAuth2AuthenticationException ex) {
            authException = ex;
        }

        assertThat(authException).isNotNull();
        assertThat(authException.getError().getErrorCode()).isEqualTo("invalid_token");
        assertThat(authException.getMessage()).contains("JWT subject claim (sub) is missing or blank");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/lessons");
        request.setAttribute(CorrelationIdAccessor.ATTRIBUTE, "corr-sub-missing-401");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authenticationEntryPoint.commence(request, response, authException);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).contains("Bearer error=\"invalid_token\"");

        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
        assertThat(body).contains("\"status\":401");
        assertThat(body).contains("\"correlationId\":\"corr-sub-missing-401\"");
        assertThat(body).contains("\"type\":\"urn:lyreo:problem:authentication-required\"");
        assertThat(body).doesNotContain("\"properties\"");
    }

    @Test
    void problemDetailExtensionsAreSerializedAtTopLevelWithoutPropertiesNesting() throws Exception {
        ProblemDetail problem = ProblemDetail.forStatus(403);
        problem.setType(URI.create("urn:lyreo:problem:access-denied"));
        problem.setTitle("Access denied");
        problem.setProperty("code", "ACCESS_DENIED");
        problem.setProperty("correlationId", "corr-top-level-test");

        String json = TestMappers.productionJsonMapper().writeValueAsString(problem);

        assertThat(json).contains("\"code\":\"ACCESS_DENIED\"");
        assertThat(json).contains("\"correlationId\":\"corr-top-level-test\"");
        assertThat(json).doesNotContain("\"properties\"");

        JsonNode rootNode = TestMappers.productionJsonMapper().readTree(json);
        assertThat(rootNode.has("code")).isTrue();
        assertThat(rootNode.get("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(rootNode.has("correlationId")).isTrue();
        assertThat(rootNode.get("correlationId").asText()).isEqualTo("corr-top-level-test");
        assertThat(rootNode.has("properties")).isFalse();
    }
}
