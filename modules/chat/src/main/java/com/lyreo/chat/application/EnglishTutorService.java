package com.lyreo.chat.application;

import com.lyreo.ai.application.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import java.util.Map;

public final class EnglishTutorService {
    private final AiInvocationService ai;
    private final EnglishTutorPrompt prompts;

    public EnglishTutorService(AiInvocationService ai, EnglishTutorPrompt prompts) {
        this.ai = ai;
        this.prompts = prompts;
    }

    public String ask(String learnerLevel, String question) {
        var result = ai.execute(
            AiCapability.REASONING_LLM,
            prompts.systemPrompt(learnerLevel),
            Map.of("question", question),
            Map.of("response_format", "text")
        );
        Object text = result.output().get("content");
        if (text == null) text = result.output().get("text");
        return text == null ? "" : text.toString();
    }
}
