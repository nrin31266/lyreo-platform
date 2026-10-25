package com.lyreo.platform.web;

import com.lyreo.ai.application.execution.*;
import com.lyreo.ai.application.port.*;
import com.lyreo.ai.application.routing.AiRouter;
import com.lyreo.ai.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreeOnlyAiInvocationTest {
    private final AiRouteRepository routes = mock(AiRouteRepository.class);
    private final AiExecutionGateway gateway = mock(AiExecutionGateway.class);
    private final AiInvocationAuditRepository audit = mock(AiInvocationAuditRepository.class);

    @Test void preservesExistingLocalTtsAndNlpRoutesWithoutExternalConfirmation() {
        var localRoutes = new ArrayList<AiRoute>();
        for (String provider : List.of("LOCAL_KOKORO", "KOKORO")) {
            for (String model : List.of("hexgrad/Kokoro-82M", "Kokoro-82M", "kokoro")) {
                localRoutes.add(new AiRoute(UUID.randomUUID(), provider, model, AiCapability.TTS, 10, false));
            }
        }
        localRoutes.add(new AiRoute(UUID.randomUUID(), "LOCAL_NLP", "lightweight-nlp", AiCapability.NLP, 10, false));
        var service = new AiInvocationService(new AiRouter(routes), routes, gateway, audit);
        for (var route : localRoutes) {
            when(routes.findEnabledRoutes(route.capability())).thenReturn(List.of(route));
            var expected = new AiExecutionResult(UUID.randomUUID(), route.providerCode(), route.model(), Map.of(), null, null, 1);
            when(gateway.execute(any())).thenReturn(expected);
            assertThat(service.execute(route.capability(), "test", Map.of(), Map.of())).isSameAs(expected);
        }
        verify(gateway, times(localRoutes.size())).execute(any());
    }

    @Test void rejectsUnknownLocalModelsAndLocalProvidersOnOtherCapabilities() {
        for (var route : List.of(
            new AiRoute(UUID.randomUUID(), "LOCAL_KOKORO", "unknown", AiCapability.TTS, 10, false),
            new AiRoute(UUID.randomUUID(), "LOCAL_KOKORO", "hexgrad/Kokoro-82M", AiCapability.GENERAL_LLM, 10, false),
            new AiRoute(UUID.randomUUID(), "LOCAL_NLP", "lightweight-nlp", AiCapability.GENERAL_LLM, 10, false))) {
            when(routes.findEnabledRoutes(route.capability())).thenReturn(List.of(route));
            var service = new AiInvocationService(new AiRouter(routes), routes, gateway, audit);
            assertThatThrownBy(() -> service.execute(route.capability(), "test", Map.of(), Map.of()))
                .isInstanceOfSatisfying(AiExecutionException.class,
                    error -> assertThat(error.errorCode()).isEqualTo("AI_FREE_ONLY_ROUTE_BLOCKED"));
        }
        verifyNoInteractions(gateway, audit);
    }

    @Test void blocksPaidAndUnconfirmedRoutesBeforeReadingCredentialOrCallingProvider() {
        for (String provider : List.of("DEEPSEEK", "GEMINI")) {
            when(routes.findEnabledRoutes(AiCapability.GENERAL_LLM)).thenReturn(List.of(
                new AiRoute(UUID.randomUUID(), provider, "gemini-3.5-flash-lite", AiCapability.GENERAL_LLM, 10, false)));
            var service = new AiInvocationService(new AiRouter(routes), routes, gateway, audit);
            assertThatThrownBy(() -> service.execute(AiCapability.GENERAL_LLM, "test", Map.of(), Map.of()))
                .isInstanceOfSatisfying(AiExecutionException.class,
                    e -> assertThat(e.errorCode()).isEqualTo("AI_FREE_ONLY_ROUTE_BLOCKED"));
        }
        verify(routes, never()).decryptedCredentialFor(any());
        verifyNoInteractions(gateway, audit);
    }

    @Test void refusesOversizedGroqFallbackWithoutChangingContextOrSendingRequest() {
        when(routes.findEnabledRoutes(AiCapability.GENERAL_LLM)).thenReturn(List.of(
            new AiRoute(UUID.randomUUID(), "GROQ", "openai/gpt-oss-120b", AiCapability.GENERAL_LLM, 10, false)));
        var service = new AiInvocationService(new AiRouter(routes), routes, gateway, audit, Set.of("GROQ"));
        assertThatThrownBy(() -> service.execute(AiCapability.GENERAL_LLM, "a".repeat(8000), Map.of(), Map.of()))
            .isInstanceOfSatisfying(AiExecutionException.class,
                e -> assertThat(e.errorCode()).isEqualTo("AI_FREE_TOKEN_BUDGET_EXCEEDED"));
        verifyNoInteractions(gateway);
    }

    @Test void retainsQuotaRetryTimeAfterTheConfiguredFreeRoutesAreExhausted() {
        when(routes.findEnabledRoutes(AiCapability.GENERAL_LLM)).thenReturn(List.of(
            new AiRoute(UUID.randomUUID(), "GEMINI", "gemini-3.5-flash-lite", AiCapability.GENERAL_LLM, 10, false)));
        when(gateway.execute(any())).thenThrow(new AiExecutionException("quota", true, "AI_HTTP_429").retryAfter(120));
        var service = new AiInvocationService(new AiRouter(routes), routes, gateway, audit, Set.of("GEMINI"));
        assertThatThrownBy(() -> service.execute(AiCapability.GENERAL_LLM, "test", Map.of(), Map.of()))
            .isInstanceOfSatisfying(AiExecutionException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(120));
    }

    @Test void waitsForPrimaryQuotaWhenTheFallbackRequestCannotFit() {
        when(routes.findEnabledRoutes(AiCapability.GENERAL_LLM)).thenReturn(List.of(
            new AiRoute(UUID.randomUUID(), "GEMINI", "gemini-3.5-flash-lite", AiCapability.GENERAL_LLM, 10, false),
            new AiRoute(UUID.randomUUID(), "GROQ", "openai/gpt-oss-120b", AiCapability.GENERAL_LLM, 20, true)));
        when(gateway.execute(any())).thenThrow(new AiExecutionException("quota", true, "AI_HTTP_429").retryAfter(120));
        var service = new AiInvocationService(new AiRouter(routes), routes, gateway, audit, Set.of("GEMINI", "GROQ"));
        assertThatThrownBy(() -> service.execute(AiCapability.GENERAL_LLM, "a".repeat(8000), Map.of(), Map.of()))
            .isInstanceOfSatisfying(AiExecutionException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(120));
        verify(gateway, times(1)).execute(any());
    }
}
