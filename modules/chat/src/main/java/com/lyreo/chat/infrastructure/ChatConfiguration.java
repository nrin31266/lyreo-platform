package com.lyreo.chat.infrastructure;

import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.chat.application.EnglishTutorPrompt;
import com.lyreo.chat.application.EnglishTutorService;
import com.lyreo.chat.application.SpeakingRoleplayService;
import com.lyreo.chat.application.port.ChatRepository;
import com.lyreo.chat.application.port.SpeakingScenarioRepository;
import com.lyreo.chat.infrastructure.persistence.JdbcChatRepository;
import com.lyreo.chat.infrastructure.persistence.JdbcSpeakingScenarioRepository;
import com.lyreo.entitlement.api.EntitlementService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

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
    SpeakingScenarioRepository speakingScenarioRepository(
        NamedParameterJdbcTemplate jdbc,
        ObjectProvider<ObjectMapper> mapperProvider
    ) {
        return new JdbcSpeakingScenarioRepository(jdbc, mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    EnglishTutorService englishTutorService(
        AiInvocationService ai,
        EnglishTutorPrompt prompt,
        ObjectProvider<EntitlementService> entitlementsProvider,
        ObjectProvider<ChatRepository> chatRepositoryProvider
    ) {
        return new EnglishTutorService(
            ai,
            prompt,
            entitlementsProvider.getIfAvailable(),
            chatRepositoryProvider.getIfAvailable()
        );
    }

    @Bean
    SpeakingRoleplayService speakingRoleplayService(
        SpeakingScenarioRepository scenarioRepository,
        AiInvocationService ai,
        ObjectProvider<EntitlementService> entitlementsProvider
    ) {
        return new SpeakingRoleplayService(scenarioRepository, ai, entitlementsProvider.getIfAvailable());
    }
}
