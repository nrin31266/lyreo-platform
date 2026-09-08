package com.lyreo.ai.infrastructure;

import com.lyreo.ai.application.AiExecutionCommand;
import com.lyreo.ai.application.AiExecutionException;
import com.lyreo.ai.application.AiExecutionGateway;
import com.lyreo.ai.application.AiExecutionResult;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** HTTP adapter from Java AI routing to the private FastAPI capability service. */
public final class FastApiAiExecutionGateway implements AiExecutionGateway {
    private final WebClient client;
    private final Duration timeout;
    private final String internalToken;

    public FastApiAiExecutionGateway(WebClient client, Duration timeout, String internalToken) {
        this.client = client;
        this.timeout = timeout;
        this.internalToken = internalToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiExecutionResult execute(AiExecutionCommand command) {
        String path = switch (command.capability()) {
            case STT -> "/v1/stt";
            case ALIGNMENT -> "/v1/align";
            case TTS -> "/v1/tts";
            case NLP -> "/v1/nlp/analyze";
            case PRONUNCIATION_JUDGE -> "/v1/multimodal/judge";
            case GENERAL_LLM, REASONING_LLM -> "/v1/llm/generate";
        };

        Map<String, Object> body = Map.of(
            "invocation_id", command.invocationId().toString(),
            "provider", command.provider(),
            "model", command.model(),
            "prompt", command.prompt() == null ? "" : command.prompt(),
            "input", command.input() == null ? Map.of() : command.input(),
            "options", command.options() == null ? Map.of() : command.options()
        );

        long start = System.nanoTime();
        try {
            Map<String, Object> response = client.post()
                .uri(path)
                .header("X-Lyreo-Internal-Token", internalToken)
                // Provider credentials are forwarded only over the private Core -> FastAPI
                // channel. Never log request headers for this client.
                .headers(headers -> {
                    if (command.credential() != null && !command.credential().isBlank()) {
                        headers.set("X-Lyreo-Provider-Credential", command.credential());
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(timeout);

            long latency = elapsedMs(start);
            if (response == null) {
                throw new AiExecutionException(
                    "AI service returned empty response", true, "AI_EMPTY_RESPONSE"
                );
            }

            Map<String, Object> output = response.get("output") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : response;
            Map<String, Object> usage = response.get("usage") instanceof Map<?, ?> rawUsage
                ? (Map<String, Object>) rawUsage
                : Map.of();

            return new AiExecutionResult(
                command.invocationId(),
                command.provider(),
                command.model(),
                output,
                asInteger(usage.get("input_tokens")),
                asInteger(usage.get("output_tokens")),
                latency
            );
        } catch (WebClientResponseException responseFailure) {
            int status = responseFailure.getStatusCode().value();
            boolean fallbackAllowed = status == 401
                || status == 403
                || status == 404
                || status == 408
                || status == 409
                || status == 425
                || status == 429
                || status >= 500;
            throw new AiExecutionException(
                "AI service/provider rejected request with HTTP " + status,
                fallbackAllowed,
                "AI_HTTP_" + status,
                responseFailure
            );
        } catch (WebClientRequestException networkFailure) {
            throw new AiExecutionException(
                "AI service network request failed", true, "AI_NETWORK", networkFailure
            );
        } catch (AiExecutionException known) {
            throw known;
        } catch (RuntimeException failure) {
            // Reactor timeout and unexpected client/runtime failures are treated as route-level
            // failures; the router may try a fallback and the durable job may retry later.
            throw new AiExecutionException(
                "AI capability execution failed",
                true,
                "AI_EXECUTION_FAILED",
                failure
            );
        }
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
