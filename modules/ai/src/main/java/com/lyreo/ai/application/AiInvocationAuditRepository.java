package com.lyreo.ai.application;

import com.lyreo.ai.domain.AiCapability;
import java.time.Instant;
import java.util.UUID;

public interface AiInvocationAuditRepository {
    void started(UUID id, AiCapability capability, String provider, String model, Instant startedAt, String requestHash);
    void succeeded(UUID id, Instant finishedAt, long latencyMs, Integer inputTokens, Integer outputTokens);
    void failed(UUID id, Instant finishedAt, long latencyMs, String errorCode);
}
