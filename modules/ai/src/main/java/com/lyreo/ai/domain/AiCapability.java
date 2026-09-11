package com.lyreo.ai.domain;

import org.springframework.modulith.NamedInterface;

/** Capability vocabulary shared with other modules; AiRoute remains internal. */
@NamedInterface("domain")
public enum AiCapability {
    STT,
    ALIGNMENT,
    GENERAL_LLM,
    REASONING_LLM,
    TTS,
    PRONUNCIATION_JUDGE,
    NLP
}
