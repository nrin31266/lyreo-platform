package com.lyreo.chat.infrastructure;

import com.lyreo.ai.application.AiInvocationService;
import com.lyreo.chat.application.EnglishTutorPrompt;
import com.lyreo.chat.application.EnglishTutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfiguration {
    @Bean
    EnglishTutorPrompt englishTutorPrompt() {
        return new EnglishTutorPrompt();
    }

    @Bean
    EnglishTutorService englishTutorService(
        AiInvocationService ai,
        EnglishTutorPrompt prompt
    ) {
        return new EnglishTutorService(ai, prompt);
    }
}
