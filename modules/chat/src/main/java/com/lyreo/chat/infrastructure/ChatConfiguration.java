package com.lyreo.chat.infrastructure;

import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.chat.application.EnglishTutorPrompt;
import com.lyreo.chat.application.EnglishTutorService;
import com.lyreo.chat.application.port.ChatRepository;
import com.lyreo.chat.infrastructure.persistence.JdbcChatRepository;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class ChatConfiguration {
    @Bean
    EnglishTutorPrompt englishTutorPrompt() {
        return new EnglishTutorPrompt();
    }

    @Bean
    ChatRepository chatRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcChatRepository(jdbc);
    }

    @Bean
    EnglishTutorService englishTutorService(
        AiInvocationService ai,
        EnglishTutorPrompt prompt,
        ObjectProvider<EntitlementService> entitlementsProvider,
        ObjectProvider<ChatRepository> chatRepositoryProvider,
        ObjectProvider<OperationIdempotencyService> idempotencyProvider
    ) {
        return new EnglishTutorService(
            ai,
            prompt,
            entitlementsProvider.getIfAvailable(),
            chatRepositoryProvider.getIfAvailable(),
            idempotencyProvider.getIfAvailable()
        );
    }
}
