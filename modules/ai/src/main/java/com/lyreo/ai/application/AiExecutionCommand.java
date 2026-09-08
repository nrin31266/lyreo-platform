package com.lyreo.ai.application;

import com.lyreo.ai.domain.AiCapability;
import java.util.Map;
import java.util.UUID;

public record AiExecutionCommand(
    UUID invocationId,
    AiCapability capability,
    String provider,
    String model,
    String credential,
    String prompt,
    Map<String, Object> input,
    Map<String, Object> options
) {}
