package com.lyreo.ai.application;

import java.util.Map;
import java.util.UUID;

/**
 * Result of one concrete provider/model invocation.
 * invocationId links business workflow steps to the durable ai_invocation audit row.
 */
public record AiExecutionResult(
    UUID invocationId,
    String provider,
    String model,
    Map<String, Object> output,
    Integer inputTokens,
    Integer outputTokens,
    long latencyMs
) {}
