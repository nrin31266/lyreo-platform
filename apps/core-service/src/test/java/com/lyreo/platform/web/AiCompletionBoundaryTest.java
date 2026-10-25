package com.lyreo.platform.web;

import com.lyreo.ai.application.execution.AiExecutionCommand;
import com.lyreo.ai.application.execution.AiExecutionException;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.infrastructure.integration.FastApiAiExecutionGateway;
import com.lyreo.ai.infrastructure.AiConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

class AiCompletionBoundaryTest {
    private AiExecutionCommand command() {
        return new AiExecutionCommand(UUID.randomUUID(), AiCapability.GENERAL_LLM, "GEMINI", "test-model",
            "test-only", "prompt", Map.of(), Map.of());
    }

    @Test void rejectsTruncationEvenWhenReturnedJsonIsSyntacticallyValid() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/llm/generate", exchange -> {
            byte[] body = "{\"output\":{\"structured\":{}},\"metadata\":{\"finish_reason\":\"length\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var gateway = new FastApiAiExecutionGateway(WebClient.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5), "test-internal");
            assertThatThrownBy(() -> gateway.execute(command())).isInstanceOfSatisfying(AiExecutionException.class,
                error -> assertThat(error.errorCode()).isEqualTo("AI_OUTPUT_INCOMPLETE"));
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"finish_reason\":null}", "{\"status\":null}", "{\"finish_reason\":\"stop\"}"})
    void requiresExplicitCompletedStatusFromGemini(String metadata) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/llm/generate", exchange -> {
            byte[] body = ("{\"output\":{\"structured\":{}},\"metadata\":" + metadata + "}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var gateway = new FastApiAiExecutionGateway(WebClient.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5), "test-internal");
            assertThatThrownBy(() -> gateway.execute(command())).isInstanceOfSatisfying(AiExecutionException.class,
                error -> assertThat(error.errorCode()).isEqualTo("AI_OUTPUT_INCOMPLETE"));
        } finally { server.stop(0); }
    }

    @Test void configuredGatewayAcceptsLongTranscriptResponsesAboveDefaultCodecLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String transcript = "word ".repeat(70_000);
        server.createContext("/v1/stt", exchange -> {
            byte[] body = ("{\"output\":{\"text\":\"" + transcript + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var factory = AiConfiguration.class.getDeclaredMethod("aiExecutionGateway", WebClient.Builder.class,
                String.class, String.class, long.class);
            factory.setAccessible(true);
            var gateway = (FastApiAiExecutionGateway) factory.invoke(new AiConfiguration(), WebClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-internal", 5L);
            var command = new AiExecutionCommand(UUID.randomUUID(), AiCapability.STT, "GROQ", "test-model",
                "test-only", "", Map.of(), Map.of());
            assertThat(gateway.execute(command).output().get("text")).isEqualTo(transcript);
        } finally { server.stop(0); }
    }

    @Test void preservesRetryAfterAndDoesNotRetryAuthenticationFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(429);
        server.createContext("/v1/llm/generate", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "123");
            exchange.sendResponseHeaders(status.get(), -1); exchange.close();
        });
        server.start();
        try {
            var gateway = new FastApiAiExecutionGateway(WebClient.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5), "test-internal");
            assertThatThrownBy(() -> gateway.execute(command())).isInstanceOfSatisfying(AiExecutionException.class,
                error -> assertThat(error.retryAfterSeconds()).isEqualTo(123));
            status.set(401);
            assertThatThrownBy(() -> gateway.execute(command())).isInstanceOfSatisfying(AiExecutionException.class,
                error -> assertThat(error.fallbackAllowed()).isFalse());
        } finally { server.stop(0); }
    }
}
