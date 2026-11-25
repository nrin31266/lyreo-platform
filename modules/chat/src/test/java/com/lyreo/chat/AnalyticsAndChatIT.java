package com.lyreo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.analytics.application.LearningAnalyticsListener;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.entitlement.api.QuotaExceededException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = AnalyticsAndChatIT.TestConfig.class)
class AnalyticsAndChatIT {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void flywayLocation(DynamicPropertyRegistry registry) {
        Path migrations = Path.of("../../apps/core-service/src/main/resources/db/migration")
            .toAbsolutePath()
            .normalize();
        registry.add("spring.flyway.locations", () -> "filesystem:" + migrations);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableMethodSecurity
    @EnableJpaRepositories(basePackages = "com.lyreo.identity")
    @EntityScan(basePackages = "com.lyreo.identity")
    @ComponentScan(basePackages = {
        "com.lyreo.analytics",
        "com.lyreo.chat",
        "com.lyreo.entitlement",
        "com.lyreo.identity",
        "com.lyreo.platform.config"
    })
    static class TestConfig {
        @Bean
        AiInvocationService aiInvocationService() {
            return mock(AiInvocationService.class);
        }

        @Bean
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
        }

        @RestControllerAdvice
        static class TestErrors {
            @ExceptionHandler(ResourceNotFoundException.class)
            ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException ex) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", ex.getMessage()));
            }

            @ExceptionHandler(QuotaExceededException.class)
            ResponseEntity<Map<String, String>> quotaExceeded(QuotaExceededException ex) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "code", "QUOTA_EXCEEDED",
                    "featureKey", ex.featureKey().value()
                ));
            }

            @ExceptionHandler(RequestValidationException.class)
            ResponseEntity<Map<String, String>> badRequest(RequestValidationException ex) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", ex.getMessage()));
            }
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private LearningAnalyticsListener analyticsListener;

    @Autowired
    private AiInvocationService aiInvocationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private MockMvc mvc;

    @BeforeEach
    void setupMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void analyticsReturnsHonestEmptyStateDeduplicatesFactsAndProjectsWeaknesses() throws Exception {
        String subject = "anl-learner-" + UUID.randomUUID();
        var learner = auth(subject);

        // Strictly honest empty state when learner has no history
        mvc.perform(get("/api/v1/analytics/overview").with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasHistory").value(false))
            .andExpect(jsonPath("$.today.lessonCount").value(0))
            .andExpect(jsonPath("$.week.grammarAnswerCount").value(0))
            .andExpect(jsonPath("$.skills").isEmpty())
            .andExpect(jsonPath("$.weaknesses").isEmpty());

        UUID learnerId = jdbc.queryForObject(
            "SELECT id FROM app_user WHERE keycloak_subject = ?",
            UUID.class,
            subject
        );

        UUID topicId = UUID.randomUUID();
        UUID headwordId = UUID.randomUUID();
        Instant now = Instant.now();

        // Grammar wrong answer -> projects weakness GRAMMAR_TOPIC, and replay does NOT double count
        var grammarWrong = new GrammarQuestionAnsweredEvent(
            learnerId, UUID.randomUUID(), UUID.randomUUID(), topicId, UUID.randomUUID(), 2, false, now
        );
        analyticsListener.onGrammarAnswered(grammarWrong);
        analyticsListener.onGrammarAnswered(grammarWrong);

        // Vocabulary AGAIN review -> projects weakness VOCABULARY
        var vocabAgain = new VocabularyReviewCompletedEvent(
            UUID.randomUUID(), learnerId, UUID.randomUUID(), headwordId, "AGAIN", false, now
        );
        analyticsListener.onVocabularyReviewed(vocabAgain);
        analyticsListener.onVocabularyReviewed(vocabAgain);

        // TOEIC attempt with errors in listening -> projects TOEIC_SECTION LISTENING weakness
        var toeic = new ToeicAttemptCompletedEvent(
            learnerId, UUID.randomUUID(), UUID.randomUUID(), "DRILL",
            2, 5, 5, 5, null, null, now
        );
        analyticsListener.onToeicCompleted(toeic);
        analyticsListener.onToeicCompleted(toeic);

        mvc.perform(get("/api/v1/analytics/overview").with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasHistory").value(true))
            .andExpect(jsonPath("$.today.grammarAnswerCount").value(1))
            .andExpect(jsonPath("$.today.vocabularyReviewCount").value(1))
            .andExpect(jsonPath("$.today.toeicQuestionCount").value(10))
            .andExpect(jsonPath("$.skills.length()").value(4))
            .andExpect(jsonPath("$.weaknesses.length()").value(3));
    }

    @Test
    void chatEnforcesQuotaCallsGeneralLlmPersistsHistoryAndRestrictsOwnership() throws Exception {
        String subjectA = "chat-a-" + UUID.randomUUID();
        String subjectB = "chat-b-" + UUID.randomUUID();
        var learnerA = auth(subjectA);
        var learnerB = auth(subjectB);

        // Provision learnerB too
        mvc.perform(get("/api/v1/chat/conversations").with(learnerB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        when(aiInvocationService.execute(eq(AiCapability.GENERAL_LLM), anyString(), anyMap(), anyMap()))
            .thenAnswer(invocation -> {
                UUID invId = UUID.randomUUID();
                jdbc.update(
                    """
                    INSERT INTO ai_invocation(id, capability, provider_code, model, status, request_hash, started_at)
                    VALUES (?, 'GENERAL_LLM', 'DEV_MOCK', 'mock', 'SUCCEEDED', 'chat-test', now())
                    """,
                    invId
                );
                return new AiExecutionResult(
                    invId,
                    "DEV_MOCK",
                    "mock",
                    Map.of("content", "Use 'since' with a starting point in time."),
                    12,
                    18,
                    42L
                );
            });

        // Ask first question -> creates conversation and persists USER + ASSISTANT messages
        String askBody = mvc.perform(post("/api/v1/chat/ask")
                .with(learnerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"learnerLevel\":\"B1\",\"question\":\"When do I use since vs for?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversationId").isNotEmpty())
            .andExpect(jsonPath("$.messageId").isNotEmpty())
            .andExpect(jsonPath("$.answer").value("Use 'since' with a starting point in time."))
            .andExpect(jsonPath("$.remainingQuota").value(9))
            .andReturn()
            .getResponse()
            .getContentAsString();

        UUID conversationId = UUID.fromString(mapper.readTree(askBody).get("conversationId").asString());
        verify(aiInvocationService).execute(eq(AiCapability.GENERAL_LLM), anyString(), anyMap(), anyMap());

        // Owner A can list and read conversation with 2 messages (USER + ASSISTANT)
        mvc.perform(get("/api/v1/chat/conversations").with(learnerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(conversationId.toString()));

        mvc.perform(get("/api/v1/chat/conversations/{id}", conversationId).with(learnerA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversation.id").value(conversationId.toString()))
            .andExpect(jsonPath("$.messages.length()").value(2))
            .andExpect(jsonPath("$.messages[0].role").value("USER"))
            .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"));

        // Non-owner B gets 404 on GET and POST into A's conversation
        mvc.perform(get("/api/v1/chat/conversations/{id}", conversationId).with(learnerB))
            .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/chat/ask")
                .with(learnerB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "conversationId", conversationId,
                    "learnerLevel", "B1",
                    "question", "Can I hijack this?"
                ))))
            .andExpect(status().isNotFound());

        // Exhaust remaining 9 FREE daily chat.tutor turns for learner A
        for (int i = 0; i < 9; i++) {
            mvc.perform(post("/api/v1/chat/ask")
                    .with(learnerA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of(
                        "conversationId", conversationId,
                        "learnerLevel", "B1",
                        "question", "Follow-up " + i
                    ))))
                .andExpect(status().isOk());
        }

        // 11th request must be rejected with 403 QUOTA_EXCEEDED BEFORE calling AI!
        mvc.perform(post("/api/v1/chat/ask")
                .with(learnerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"learnerLevel\":\"B1\",\"question\":\"One too many\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        verify(aiInvocationService, times(10)).execute(eq(AiCapability.GENERAL_LLM), any(), anyMap(), anyMap());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM chat_message WHERE conversation_id = ?",
            Integer.class,
            conversationId
        )).isEqualTo(20);
    }

    private static RequestPostProcessor auth(String subject) {
        return jwt()
            .jwt(token -> token.subject(subject).claim("email", subject + "@example.test"))
            .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
    }
}
