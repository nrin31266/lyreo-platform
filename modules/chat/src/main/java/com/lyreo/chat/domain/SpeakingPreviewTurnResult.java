package com.lyreo.chat.domain;

import java.util.List;
import java.util.UUID;

public record SpeakingPreviewTurnResult(
    String assistantReply,
    String ttsVoice,
    UUID aiInvocationId,
    String provider,
    long latencyMs,
    List<String> coachingTips
) {}
