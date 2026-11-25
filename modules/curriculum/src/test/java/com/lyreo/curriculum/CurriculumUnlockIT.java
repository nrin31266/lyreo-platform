package com.lyreo.curriculum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.contracts.grammar.GrammarSessionCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.curriculum.application.CurriculumCompletionListener;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

@SpringBootTest(classes = CurriculumUnlockIT.TestConfig.class)
class CurriculumUnlockIT {

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
        "com.lyreo.curriculum",
        "com.lyreo.identity",
        "com.lyreo.platform.config"
    })
    static class TestConfig {
        @Bean
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
                )
                .build();
        }

        @RestControllerAdvice
        static class TestErrors {
            @ExceptionHandler(ResourceNotFoundException.class)
            ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException ex) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", ex.getMessage()));
            }

            @ExceptionHandler(StateConflictException.class)
            ResponseEntity<Map<String, String>> conflict(StateConflictException ex) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", ex.getMessage()));
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
    private CurriculumCompletionListener listener;

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
    void enforcesEnrollmentPublishedStatusSequentialAndOptionalUnlockAndDeduplication() throws Exception {
        UUID lessonId = UUID.randomUUID();
        UUID optionalVocabUnitId = UUID.randomUUID();
        UUID grammarCatalogId = UUID.randomUUID();
        UUID toeicDrillCatalogId = UUID.randomUUID();

        var admin = auth("cur-admin-" + UUID.randomUUID(), "ADMIN");
        var learner = auth("cur-learner-" + UUID.randomUUID(), "LEARNER");

        String createPath1 = mapper.writeValueAsString(Map.of(
            "code", "path-1-" + UUID.randomUUID(),
            "name", "Foundation Path",
            "level", "B1",
            "description", "Core English path",
            "status", "DRAFT",
            "sections", List.of(
                Map.of(
                    "title", "Section 1",
                    "position", 1,
                    "items", List.of(
                        Map.of("position", 1, "contentType", "LESSON", "contentReferenceId", lessonId, "required", true),
                        Map.of("position", 2, "contentType", "VOCABULARY_REVIEW", "contentReferenceId", optionalVocabUnitId, "required", false),
                        Map.of("position", 3, "contentType", "GRAMMAR_PRACTICE", "contentReferenceId", grammarCatalogId, "required", true)
                    )
                ),
                Map.of(
                    "title", "Section 2",
                    "position", 2,
                    "items", List.of(
                        Map.of("position", 1, "contentType", "TOEIC_DRILL", "contentReferenceId", toeicDrillCatalogId, "required", true)
                    )
                )
            )
        ));

        String path1Json = mvc.perform(post("/api/v1/admin/curriculum/paths")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPath1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        UUID path1Id = UUID.fromString(mapper.readTree(path1Json).get("id").asString());

        // Second published path referencing the same lessonId, but learner never enrolls in it
        String createPath2 = mapper.writeValueAsString(Map.of(
            "code", "path-2-" + UUID.randomUUID(),
            "name", "Unenrolled Path",
            "level", "B2",
            "status", "PUBLISHED",
            "sections", List.of(Map.of(
                "title", "Only Section",
                "position", 1,
                "items", List.of(
                    Map.of("position", 1, "contentType", "LESSON", "contentReferenceId", lessonId, "required", true)
                )
            ))
        ));
        String path2Json = mvc.perform(post("/api/v1/admin/curriculum/paths")
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPath2))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        UUID path2Id = UUID.fromString(mapper.readTree(path2Json).get("id").asString());

        // Learner cannot enroll in DRAFT path1
        mvc.perform(post("/api/v1/curriculum/paths/{id}/enroll", path1Id).with(learner))
            .andExpect(status().isConflict());

        // Publish path1
        mvc.perform(put("/api/v1/admin/curriculum/paths/{id}/status", path1Id)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PUBLISHED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Learner provisions and has no active enrollment yet -> empty continueLearning
        mvc.perform(get("/api/v1/curriculum/continue").with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasNext").value(false));

        UUID learnerId = jdbc.queryForObject(
            "SELECT id FROM app_user WHERE keycloak_subject LIKE 'cur-learner-%' ORDER BY created_at DESC LIMIT 1",
            UUID.class
        );

        // Before enrollment: completion event must NOT advance any item
        listener.onLessonCompleted(new LessonCompletedEvent(UUID.randomUUID(), learnerId, lessonId, Instant.now()));
        Integer progressCountBeforeEnroll = jdbc.queryForObject(
            "SELECT count(*) FROM curriculum_item_progress WHERE learner_id = ?",
            Integer.class,
            learnerId
        );
        assertThat(progressCountBeforeEnroll).isZero();

        // Enroll in path1
        mvc.perform(post("/api/v1/curriculum/paths/{id}/enroll", path1Id).with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enrollmentStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.sections[0].items[0].progressStatus").value("UNLOCKED"))
            .andExpect(jsonPath("$.sections[0].items[1].progressStatus").value("LOCKED"))
            .andExpect(jsonPath("$.sections[0].items[2].progressStatus").value("LOCKED"))
            .andExpect(jsonPath("$.sections[1].items[0].progressStatus").value("LOCKED"));

        // Continue learning points to item 1 (lesson)
        mvc.perform(get("/api/v1/curriculum/continue").with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.contentReferenceId").value(lessonId.toString()))
            .andExpect(jsonPath("$.contentType").value(ContentType.LESSON.name()));

        // Complete lesson1 -> unlocks BOTH optional vocab item AND required grammar item!
        LessonCompletedEvent lessonEvent = new LessonCompletedEvent(UUID.randomUUID(), learnerId, lessonId, Instant.now());
        listener.onLessonCompleted(lessonEvent);
        // Replay same factId -> deduplicated
        listener.onLessonCompleted(lessonEvent);

        mvc.perform(get("/api/v1/curriculum/paths/{id}", path1Id).with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections[0].items[0].progressStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.sections[0].items[1].progressStatus").value("UNLOCKED"))
            .andExpect(jsonPath("$.sections[0].items[2].progressStatus").value("UNLOCKED"))
            .andExpect(jsonPath("$.sections[1].items[0].progressStatus").value("LOCKED"))
            .andExpect(jsonPath("$.completedRequiredItems").value(1))
            .andExpect(jsonPath("$.totalRequiredItems").value(3));

        // Unenrolled path2 must NOT have any progress!
        Integer path2Progress = jdbc.queryForObject(
            """
            SELECT count(*) FROM curriculum_item_progress cip
            JOIN curriculum_item ci ON ci.id = cip.item_id
            JOIN curriculum_section cs ON cs.id = ci.section_id
            WHERE cip.learner_id = ? AND cs.path_id = ?
            """,
            Integer.class,
            learnerId,
            path2Id
        );
        assertThat(path2Progress).isZero();

        // Continue learning skips optional vocab item and points to required grammar item
        mvc.perform(get("/api/v1/curriculum/continue").with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentReferenceId").value(grammarCatalogId.toString()))
            .andExpect(jsonPath("$.contentType").value(ContentType.GRAMMAR_PRACTICE.name()));

        // Complete grammar session -> unlocks section 2 TOEIC drill
        listener.onGrammarSessionCompleted(new GrammarSessionCompletedEvent(
            UUID.randomUUID(), learnerId, grammarCatalogId, "TOPIC", 5, 5, Instant.now()
        ));

        mvc.perform(get("/api/v1/curriculum/paths/{id}", path1Id).with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections[0].items[2].progressStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.sections[1].items[0].progressStatus").value("UNLOCKED"));

        // Complete TOEIC drill (optional vocab item still uncompleted) -> completes entire enrollment!
        listener.onToeicCompleted(new ToeicAttemptCompletedEvent(
            learnerId, UUID.randomUUID(), toeicDrillCatalogId, "DRILL",
            5, 5, 0, 0, null, null, Instant.now()
        ));

        mvc.perform(get("/api/v1/curriculum/paths/{id}", path1Id).with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enrollmentStatus").value("COMPLETED"))
            .andExpect(jsonPath("$.completedRequiredItems").value(3))
            .andExpect(jsonPath("$.sections[0].items[1].progressStatus").value("UNLOCKED"))
            .andExpect(jsonPath("$.sections[1].items[0].progressStatus").value("COMPLETED"));

        // Path enrollment is COMPLETED, so continueLearning has no active path left
        mvc.perform(get("/api/v1/curriculum/continue").with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasNext").value(false));

        // Completing optional vocab unit AFTER enrollment became COMPLETED does NOT advance COMPLETED enrollment
        listener.onVocabularyUnitCompleted(new VocabularyUnitCompletedEvent(
            UUID.randomUUID(), learnerId, UUID.randomUUID(), optionalVocabUnitId, 5, 5, Instant.now()
        ));
        mvc.perform(get("/api/v1/curriculum/paths/{id}", path1Id).with(learner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections[0].items[1].progressStatus").value("UNLOCKED"));
    }

    private static RequestPostProcessor auth(String subject, String role) {
        return jwt()
            .jwt(token -> token.subject(subject).claim("email", subject + "@example.test"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
