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

    @Test void selectedKokoroVoiceNeverFallsBackToAnotherProvider() {
        when(routes.findEnabledRoutes(AiCapability.TTS)).thenReturn(List.of(
            new AiRoute(UUID.randomUUID(),"DEV_MOCK","mock",AiCapability.TTS,1,false),
            new AiRoute(UUID.randomUUID(),"LOCAL_KOKORO","kokoro",AiCapability.TTS,2,false)));
        when(gateway.execute(any())).thenThrow(new AiExecutionException("Local unavailable",true,"LOCAL_UNAVAILABLE"));
        var service=new AiInvocationService(new AiRouter(routes),routes,gateway,audit);
        assertThatThrownBy(()->service.executeForProvider(AiCapability.TTS,"LOCAL_KOKORO","",Map.of("text","Hello"),Map.of("voice","bf_emma")))
            .isInstanceOf(AiExecutionException.class);
        var command=org.mockito.ArgumentCaptor.forClass(AiExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().provider()).isEqualTo("LOCAL_KOKORO");
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

    @Test void blocksUnconfirmedRoutesBeforeReadingCredentialOrCallingProvider() {
        for (String provider : List.of("GEMINI")) {
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

    @Test void allowsConfiguredDeepSeekRouteThroughNormalCredentialAndPriorityPath() {
        var route = new AiRoute(UUID.randomUUID(), "DEEPSEEK", "deepseek-flash", AiCapability.GENERAL_LLM, 1, false);
        when(routes.findEnabledRoutes(AiCapability.GENERAL_LLM)).thenReturn(List.of(route));
        when(routes.decryptedCredentialFor(route)).thenReturn("encrypted-key");
        var expected = new AiExecutionResult(UUID.randomUUID(), "DEEPSEEK", route.model(), Map.of("ok", true), 10, 5, 12);
        when(gateway.execute(any())).thenReturn(expected);
        var service = new AiInvocationService(new AiRouter(routes), routes, gateway, audit);

        assertThat(service.execute(AiCapability.GENERAL_LLM, "test", Map.of(), Map.of())).isSameAs(expected);
        var command = org.mockito.ArgumentCaptor.forClass(AiExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().provider()).isEqualTo("DEEPSEEK");
        assertThat(command.getValue().credential()).isEqualTo("encrypted-key");
    }

    @Test void deepSeekCanFallBackToGeminiWithoutAStoredFallbackFlag() {
        when(routes.findEnabledRoutes(AiCapability.GENERAL_LLM)).thenReturn(List.of(
            new AiRoute(UUID.randomUUID(),"DEEPSEEK","deepseek-flash",AiCapability.GENERAL_LLM,1,false),
            new AiRoute(UUID.randomUUID(),"GEMINI","gemini-3.5-flash-lite",AiCapability.GENERAL_LLM,2,false)));
        var service=new AiInvocationService(new AiRouter(routes),routes,gateway,audit,Set.of("GEMINI"));
        var expected=new AiExecutionResult(UUID.randomUUID(),"GEMINI","gemini-3.5-flash-lite",Map.of(),2,2,1);
        when(gateway.execute(any())).thenThrow(new AiExecutionException("rate limited",true,"AI_HTTP_429")).thenReturn(expected);
        assertThat(service.execute(AiCapability.GENERAL_LLM,"test",Map.of(),Map.of())).isSameAs(expected);
        var attempts=org.mockito.ArgumentCaptor.forClass(AiExecutionCommand.class);
        verify(gateway,times(2)).execute(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(AiExecutionCommand::provider).containsExactly("DEEPSEEK","GEMINI");

        clearInvocations(gateway);
        when(gateway.execute(any())).thenThrow(new AiExecutionException("bad request",false,"AI_HTTP_400"));
        assertThatThrownBy(()->service.execute(AiCapability.GENERAL_LLM,"test",Map.of(),Map.of()))
            .isInstanceOfSatisfying(AiExecutionException.class, e -> assertThat(e.errorCode()).isEqualTo("AI_HTTP_400"));
        verify(gateway).execute(argThat(command -> command.provider().equals("DEEPSEEK")));
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
    @Test void observesFailedUsageAndFallbackExactlyOnceAndClearsObserver() {
        when(routes.findEnabledRoutes(AiCapability.GENERAL_LLM)).thenReturn(List.of(
            new AiRoute(UUID.randomUUID(),"GEMINI","gemini-3.5-flash-lite",AiCapability.GENERAL_LLM,10,false),
            new AiRoute(UUID.randomUUID(),"GROQ","openai/gpt-oss-120b",AiCapability.GENERAL_LLM,20,true)));
        var partial=new AiExecutionResult(UUID.randomUUID(),"GEMINI","gemini-3.5-flash-lite",Map.of("partial","truncated"),20,10,8);
        var complete=new AiExecutionResult(UUID.randomUUID(),"GROQ","openai/gpt-oss-120b",Map.of(),30,15,9);
        when(gateway.execute(any())).thenThrow(new AiExecutionException("cut",true,"AI_OUTPUT_INCOMPLETE").evidence(partial)
            .diagnostics(Map.of("provider_status",503,"provider_message","Overloaded"))).thenReturn(complete);
        var service=new AiInvocationService(new AiRouter(routes),routes,gateway,audit,Set.of("GEMINI","GROQ"));
        var observed=new ArrayList<AiInvocationService.Attempt>();
        try(var scope=service.observe(observed::add)) {assertThat(service.execute(AiCapability.GENERAL_LLM,"test",Map.of(),Map.of())).isSameAs(complete);}
        assertThat(observed).hasSize(2);assertThat(observed.getFirst().inputTokens()).isEqualTo(20);
        assertThat(observed.getFirst().output()).containsEntry("partial","truncated");
        assertThat(observed.getFirst().capability()).isEqualTo("GENERAL_LLM");
        assertThat(observed.getFirst().fallback()).isFalse();
        assertThat(observed.getFirst().error()).contains("AI_OUTPUT_INCOMPLETE");
        assertThat(observed.getFirst().diagnostics()).containsEntry("provider_status",503).containsEntry("provider_message","Overloaded");
        assertThat(observed.getLast().fallback()).isTrue();
        assertThat(observed.getLast().error()).isEmpty();
        assertThat(observed.getLast().diagnostics()).isEmpty();
        assertThat(observed.getLast().outputTokens()).isEqualTo(15);
        service.execute(AiCapability.GENERAL_LLM,"test",Map.of(),Map.of());assertThat(observed).hasSize(2);
    }

}
