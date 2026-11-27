package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.application.routing.AiRoutingSnapshotService;
import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.identity.application.ProvisionedUser;
import com.lyreo.lesson.api.LearnerLessonController;
import com.lyreo.lesson.api.LearnerShadowingCompletionPort;
import com.lyreo.lesson.api.LessonPracticeController;
import com.lyreo.lesson.api.ShadowingReference;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.application.practice.LearnerLessonService;
import com.lyreo.lesson.application.practice.LessonPracticeService;
import com.lyreo.lesson.domain.authoring.AuthoringCloze;
import com.lyreo.lesson.domain.authoring.AuthoringOptions;
import com.lyreo.lesson.domain.authoring.AuthoringProfile;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.infrastructure.LessonConfiguration;
import com.lyreo.lesson.infrastructure.persistence.JdbcAuthoringRepository;
import com.lyreo.platform.config.infrastructure.RuntimeConfigConfiguration;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.storage.LocalFileObjectStorageAdapter;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LearnerLessonPracticeIT.Config.class)
class LearnerLessonPracticeIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    static Path storageRoot;

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureFlyway(DynamicPropertyRegistry registry) {
        Path migrations = Path.of("apps/core-service/src/main/resources/db/migration");
        if (!Files.isDirectory(migrations)) {
            migrations = Path.of("../../apps/core-service/src/main/resources/db/migration");
        }
        String location = "filesystem:" + migrations.toAbsolutePath().normalize();
        registry.add("spring.flyway.locations", () -> location);
    }

    @AfterAll
    static void cleanupStorage() throws IOException {
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (var paths = Files.walk(storageRoot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({LessonConfiguration.class, RuntimeConfigConfiguration.class, ShadowingReference.class})
    static class Config {
        @Bean
        ObjectStoragePort storage() throws IOException {
            storageRoot = Files.createTempDirectory("lesson-practice-it-");
            return new LocalFileObjectStorageAdapter(storageRoot);
        }

        @Bean
        AuthoringRepository authoringRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
            return new JdbcAuthoringRepository(jdbc, mapper, mock(BackgroundJobService.class));
        }

        @Bean
        BackgroundJobService backgroundJobService() {
            return mock(BackgroundJobService.class);
        }

        @Bean
        AiInvocationService aiInvocationService() {
            return mock(AiInvocationService.class);
        }

        @Bean
        AiRoutingSnapshotService aiRoutingSnapshotService() {
            return mock(AiRoutingSnapshotService.class);
        }

        @Bean
        AuthoringSourcePort authoringSourcePort() {
            return mock(AuthoringSourcePort.class);
        }

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    static class EventRecorder {
        final List<LessonActivityCompletedEvent> activityEvents = new CopyOnWriteArrayList<>();
        final List<LessonCompletedEvent> lessonEvents = new CopyOnWriteArrayList<>();

        @EventListener
        void onActivity(LessonActivityCompletedEvent event) {
            activityEvents.add(event);
        }

        @EventListener
        void onLesson(LessonCompletedEvent event) {
            lessonEvents.add(event);
        }

        void clear() {
            activityEvents.clear();
            lessonEvents.clear();
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ObjectStoragePort storage;
    @Autowired LearnerLessonService learnerLessonService;
    @Autowired LessonPracticeService lessonPracticeService;
    @Autowired LearnerShadowingCompletionPort shadowingCompletion;
    @Autowired ShadowingReference shadowingReference;
    @Autowired EventRecorder events;

    private MockMvc mvc;
    private UUID activeLearnerId;

    @BeforeEach
    void setupMvc() {
        events.clear();
        activeLearnerId = insertLearner();
        AppUserProvisioningService users = mock(AppUserProvisioningService.class);
        when(users.provision(any(), any())).thenAnswer(invocation ->
            new ProvisionedUser(activeLearnerId, invocation.getArgument(0), invocation.getArgument(1), Instant.now()));

        HandlerMethodArgumentResolver jwtResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(Jwt.class);
            }

            @Override
            public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory
            ) {
                return Jwt.withTokenValue("test-jwt")
                    .header("alg", "none")
                    .claim("sub", activeLearnerId.toString())
                    .claim("email", "learner@lyreo.test")
                    .build();
            }
        };

        mvc = MockMvcBuilders.standaloneSetup(
                new LearnerLessonController(learnerLessonService, lessonPracticeService, users),
                new LessonPracticeController(lessonPracticeService, users)
            )
            .setCustomArgumentResolvers(jwtResolver)
            .build();
    }

    @Test
    void catalogFiltersOnlyAvailableLessonsAndStreamsAudioWithRangesAndResumeMasking() throws Exception {
        UUID categoryId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO lesson_category(id, name, translations) VALUES (?, ?, CAST(? AS jsonb))",
            categoryId, "Travel " + categoryId, "{\"vi\":\"Du lịch\"}"
        );

        UUID visibleLesson = seedPublishedAuthoredLesson(
            "London Travel Guide",
            "TEXT",
            "B1",
            Set.of("DICTATION", "SHADOWING"),
            true,
            true,
            categoryId,
            "0123456789".getBytes(StandardCharsets.UTF_8)
        );
        seedPublishedAuthoredLesson(
            "Hidden Lesson",
            "TEXT",
            "B1",
            Set.of("DICTATION"),
            false,
            true,
            categoryId,
            "abcdefghij".getBytes(StandardCharsets.UTF_8)
        );
        seedPublishedAuthoredLesson(
            "Draft Lesson",
            "TEXT",
            "B1",
            Set.of("DICTATION"),
            true,
            false,
            categoryId,
            "abcdefghij".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(get("/api/v1/lessons")
                .param("categoryId", categoryId.toString())
                .param("cefr", "B1")
                .param("sourceType", "TEXT")
                .param("activity", "DICTATION")
                .param("q", "London")
                .param("page", "0")
                .param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(visibleLesson.toString()))
            .andExpect(jsonPath("$.items[0].revision").value(3))
            .andExpect(jsonPath("$.items[0].progress.status").value("NOT_STARTED"));

        mvc.perform(get("/api/v1/lessons/" + visibleLesson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(visibleLesson.toString()))
            .andExpect(jsonPath("$.revision").value(3))
            .andExpect(jsonPath("$.audioReady").value(true))
            .andExpect(jsonPath("$.sentences[0].text").doesNotExist())
            .andExpect(jsonPath("$.activities.length()").value(3));

        UUID dictationActivity = activityId(visibleLesson, "DICTATION");
        UUID shadowingActivity = activityId(visibleLesson, "SHADOWING");
        UUID watchActivity = activityId(visibleLesson, "WATCH");
        List<UUID> sentences = sentenceIds(visibleLesson);
        UUID s1 = sentences.get(0);
        UUID s2 = sentences.get(1);

        // Set learner properNounHints=false, verify full mask hides London & 2026 & translation
        jdbc.update(
            "INSERT INTO learner_profile(learner_id, preferences_json) VALUES (?, CAST(? AS jsonb))",
            activeLearnerId,
            "{\"properNounHints\":false}"
        );
        mvc.perform(get("/api/v1/lessons/" + visibleLesson + "/activities/" + dictationActivity)
                .param("hintLevel", "NONE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sentences[0].attempted").value(false))
            .andExpect(jsonPath("$.sentences[0].expectedText").doesNotExist())
            .andExpect(jsonPath("$.sentences[0].translation").doesNotExist())
            .andExpect(jsonPath("$.sentences[0].text").value("____ ____ ____ ____ ____ ____."))
            .andExpect(jsonPath("$.sentences[0].words[0].surfaceText").value("____"));

        // With properNounHints=true and EASY cloze, proper nouns/numbers stay visible and fewer blanks appear
        jdbc.update(
            "UPDATE learner_profile SET preferences_json = CAST(? AS jsonb) WHERE learner_id = ?",
            "{\"properNounHints\":true}",
            activeLearnerId
        );
        var easyView = learnerLessonService.getActivity(activeLearnerId, visibleLesson, dictationActivity, "EASY", null);
        var mediumView = learnerLessonService.getActivity(activeLearnerId, visibleLesson, dictationActivity, "MEDIUM", false);
        long easyMasked = easyView.sentences().getFirst().words().stream().filter(w -> w.masked()).count();
        long mediumMasked = mediumView.sentences().getFirst().words().stream().filter(w -> w.masked()).count();
        assertThat(mediumMasked).isGreaterThan(easyMasked);

        // Submit dictation with Idempotency-Key and verify replay + conflict
        String idemKey = UUID.randomUUID().toString();
        String body1 = """
            {"sentenceId": "%s", "answer": "Today London welcomes 2026 UK guests."}
            """.formatted(s1);
        mvc.perform(post("/api/v1/lessons/" + visibleLesson + "/activities/" + dictationActivity + "/dictation/attempts")
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serverScore").value(100))
            .andExpect(jsonPath("$.activityCompleted").value(false))
            .andExpect(jsonPath("$.lessonCompleted").value(false));

        // Replay same key & payload returns same attempt without inserting a second row
        mvc.perform(post("/api/v1/lessons/" + visibleLesson + "/activities/" + dictationActivity + "/dictation/attempts")
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serverScore").value(100));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM lesson_practice_attempt WHERE learner_id = ? AND lesson_id = ?",
            Integer.class, activeLearnerId, visibleLesson
        )).isEqualTo(1);

        // Reusing key with different payload throws IdempotencyConflictException
        assertThatThrownBy(() -> lessonPracticeService.submitDictation(
            activeLearnerId, visibleLesson, dictationActivity, s1, "Different answer", idemKey
        )).isInstanceOf(IdempotencyConflictException.class);

        // Resuming activity reveals expectedText & translation + latestAttempt for s1 while s2 stays masked
        mvc.perform(get("/api/v1/lessons/" + visibleLesson + "/activities/" + dictationActivity)
                .param("hintLevel", "NONE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sentences[0].attempted").value(true))
            .andExpect(jsonPath("$.sentences[0].expectedText").value("Today London welcomes 2026 UK guests."))
            .andExpect(jsonPath("$.sentences[0].translation").value("Hôm nay London chào đón các vị khách UK 2026."))
            .andExpect(jsonPath("$.sentences[0].latestAttempt.serverScore").value(100))
            .andExpect(jsonPath("$.sentences[1].attempted").value(false))
            .andExpect(jsonPath("$.sentences[1].expectedText").doesNotExist());

        // WATCH / SHADOWING returns full spoken text, IPA, thoughtGroups, word timestamps
        mvc.perform(get("/api/v1/lessons/" + visibleLesson + "/activities/" + watchActivity))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sentences[0].text").value("Today London welcomes 2026 UK guests."))
            .andExpect(jsonPath("$.sentences[0].ipa").isNotEmpty())
            .andExpect(jsonPath("$.sentences[0].thoughtGroups.length()").value(1))
            .andExpect(jsonPath("$.sentences[0].words[0].surfaceText").value("Today"));

        // Complete second dictation sentence -> DICTATION activity completes, but SHADOWING is still incomplete so lesson stays IN_PROGRESS
        lessonPracticeService.submitDictation(
            activeLearnerId, visibleLesson, dictationActivity, s2, "We practice daily.", UUID.randomUUID().toString()
        );
        assertThat(events.activityEvents).hasSize(1);
        assertThat(events.activityEvents.getFirst().activityType()).isEqualTo("DICTATION");
        assertThat(events.lessonEvents).isEmpty();

        // Complete SHADOWING sentences -> lesson completes WITHOUT requiring WATCH completion!
        shadowingCompletion.recordScoredAttempt(
            activeLearnerId, visibleLesson, shadowingActivity, s1, UUID.randomUUID(), 90, "Today...", "{}"
        );
        var shadowDone = shadowingCompletion.recordScoredAttempt(
            activeLearnerId, visibleLesson, shadowingActivity, s2, UUID.randomUUID(), 88, "We practice daily.", "{}"
        );
        assertThat(shadowDone.activityCompleted()).isTrue();
        assertThat(shadowDone.lessonCompleted()).isTrue();
        assertThat(events.activityEvents).hasSize(2);
        assertThat(events.lessonEvents).hasSize(1);

        // Attempt history descending
        mvc.perform(get("/api/v1/lessons/" + visibleLesson + "/attempts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.items[0].activityType").value("SHADOWING"));

        // Audio streaming with byte-range & headers
        mvc.perform(get("/api/v1/lessons/" + visibleLesson + "/audio")
                .header("Range", "bytes=2-5"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 2-5/10"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // Unlist lesson: learner WITH progress can still stream audio, new learner CANNOT
        jdbc.update("UPDATE lesson_metadata SET visible = FALSE WHERE lesson_id = ?", visibleLesson);
        mvc.perform(get("/api/v1/lessons/" + visibleLesson + "/audio"))
            .andExpect(status().isOk());

        UUID otherLearner = insertLearner();
        assertThatThrownBy(() -> learnerLessonService.readAudio(otherLearner, visibleLesson))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void watchSoleActivityCompletesLessonOnlyWhenExplicitlyCompleted() throws Exception {
        UUID watchOnlyLesson = seedPublishedAuthoredLesson(
            "Watch Only Lesson",
            "TEXT",
            "A2",
            Set.of(),
            true,
            true,
            null,
            "0123456789".getBytes(StandardCharsets.UTF_8)
        );
        UUID watchActivity = activityId(watchOnlyLesson, "WATCH");

        mvc.perform(post("/api/v1/lessons/" + watchOnlyLesson + "/activities/" + watchActivity + "/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.activityCompleted").value(true))
            .andExpect(jsonPath("$.lessonCompleted").value(true));

        assertThat(events.activityEvents).hasSize(1);
        assertThat(events.lessonEvents).hasSize(1);

        // Second call is idempotent and does not fire duplicate events
        mvc.perform(post("/api/v1/lessons/" + watchOnlyLesson + "/activities/" + watchActivity + "/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.activityCompleted").value(false))
            .andExpect(jsonPath("$.lessonCompleted").value(false));

        assertThat(events.activityEvents).hasSize(1);
        assertThat(events.lessonEvents).hasSize(1);
    }

    private UUID insertLearner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", id, "sub-" + id);
        return id;
    }

    private UUID seedPublishedAuthoredLesson(
        String title,
        String sourceType,
        String cefr,
        Set<String> activities,
        boolean visible,
        boolean published,
        UUID categoryId,
        byte[] audioBytes
    ) {
        UUID lessonId = UUID.randomUUID();
        String audioKey = "lessons/" + lessonId + "/audio.wav";
        storage.put(audioKey, "audio/wav", audioBytes);

        jdbc.update(
            "INSERT INTO lesson(id, title, source_type, canonical_audio_object_key, status) VALUES (?, ?, ?, ?, 'READY')",
            lessonId, title, sourceType, audioKey
        );
        jdbc.update(
            "UPDATE lesson_metadata SET title = ?, visible = ? WHERE lesson_id = ?",
            title, visible, lessonId
        );
        if (categoryId != null) {
            jdbc.update(
                "INSERT INTO lesson_category_membership(lesson_id, category_id) VALUES (?, ?)",
                lessonId, categoryId
            );
        }

        UUID s1Id = UUID.randomUUID();
        UUID s2Id = UUID.randomUUID();
        AuthoringSentence s1 = new AuthoringSentence(s1Id, "Today London welcomes 2026 UK guests.", "LEARN");
        s1.words = List.of(
            new AuthoringSentence.Word("Today", 0, 400, 0, 5, 0, 1),
            new AuthoringSentence.Word("London", 400, 900, 6, 12, 1, 2),
            new AuthoringSentence.Word("welcomes", 900, 1400, 13, 21, 2, 3),
            new AuthoringSentence.Word("2026", 1400, 1800, 22, 26, 3, 4),
            new AuthoringSentence.Word("UK", 1800, 2100, 27, 29, 4, 5),
            new AuthoringSentence.Word("guests", 2100, 2600, 30, 36, 5, 6)
        );
        s1.translation = "Hôm nay London chào đón các vị khách UK 2026.";
        s1.ipa = "təˈdeɪ ˈlʌndən ˈwɛlkəmz...";
        s1.ipaAccent = "US";
        s1.ipaVersion = "espeak-1";
        s1.thoughtGroups = List.of(new AuthoringSentence.Group(0, 3));
        s1.ipaReviewIssues = List.of(new AuthoringSentence.IpaReviewIssue("NAME", 6, 12, "London"));
        s1.dictationSelected = activities.contains("DICTATION");
        s1.shadowingSelected = activities.contains("SHADOWING");
        s1.cloze = new AuthoringCloze.Targets(List.of(2), List.of(1, 2, 5), "cloze-v2", s1.text);

        AuthoringSentence s2 = new AuthoringSentence(s2Id, "We practice daily.", "LEARN");
        s2.words = List.of(
            new AuthoringSentence.Word("We", 3000, 3200, 0, 2, 0, 1),
            new AuthoringSentence.Word("practice", 3200, 3800, 3, 11, 1, 2),
            new AuthoringSentence.Word("daily", 3800, 4300, 12, 17, 2, 3)
        );
        s2.translation = "Chúng tôi luyện tập hằng ngày.";
        s2.ipa = "wi ˈpræktɪs ˈdeɪli";
        s2.dictationSelected = activities.contains("DICTATION");
        s2.shadowingSelected = activities.contains("SHADOWING");
        s2.cloze = AuthoringCloze.generate(s2);

        List<AuthoringSentence> doc = List.of(s1, s2);
        AuthoringOptions options = new AuthoringOptions(
            activities,
            Set.of("TRANSLATION", "LEXICAL", "GRAMMAR", "PROFILE"),
            "vi-VN", "AUTO", 20, 8, "PREGENERATE", "US"
        );
        AuthoringProfile profile = new AuthoringProfile();
        profile.cefr = AuthoringProfile.Cefr.valueOf(cefr);
        profile.contentType = AuthoringProfile.ContentType.MONOLOGUE;

        jdbc.update("""
            INSERT INTO lesson_authoring(
                lesson_id, source_start_ms, source_end_ms, revision, document,
                published_at, generation_options, content_profile
            ) VALUES (?, 0, 5000, 3, CAST(? AS jsonb), NULL, CAST(? AS jsonb), CAST(? AS jsonb))
            """,
            lessonId,
            mapper.writeValueAsString(doc),
            mapper.writeValueAsString(options),
            mapper.writeValueAsString(profile)
        );

        if (published) {
            for (int i = 0; i < doc.size(); i++) {
                var s = doc.get(i);
                jdbc.update(
                    "INSERT INTO lesson_sentence(id, lesson_id, position, text, audio_start_ms, audio_end_ms) VALUES (?, ?, ?, ?, ?, ?)",
                    s.id, lessonId, i, s.text, s.words.getFirst().startMs(), s.words.getLast().endMs()
                );
                for (int w = 0; w < s.words.size(); w++) {
                    var word = s.words.get(w);
                    jdbc.update(
                        "INSERT INTO lesson_word_timestamp(id, sentence_id, position, surface_text, start_ms, end_ms) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), s.id, w, word.text(), word.startMs(), word.endMs()
                    );
                }
                jdbc.update(
                    "INSERT INTO lesson_annotation(id, lesson_id, sentence_id, annotation_type, payload_json) VALUES (?, ?, ?, 'CONTENT', CAST(? AS jsonb))",
                    UUID.randomUUID(), lessonId, s.id, mapper.writeValueAsString(s)
                );
            }
            var allActivities = new java.util.TreeSet<>(activities);
            allActivities.add("WATCH");
            for (String type : allActivities) {
                UUID actId = UUID.randomUUID();
                int pos = type.equals("WATCH") ? 0 : type.equals("DICTATION") ? 1 : 2;
                jdbc.update(
                    "INSERT INTO lesson_activity(id, lesson_id, activity_type, position, enabled) VALUES (?, ?, ?, ?, TRUE)",
                    actId, lessonId, type, pos
                );
                for (var s : doc) {
                    if ("WATCH".equals(type) || ("DICTATION".equals(type) ? s.dictationSelected : s.shadowingSelected)) {
                        jdbc.update(
                            "INSERT INTO lesson_activity_sentence(activity_id, sentence_id) VALUES (?, ?)",
                            actId, s.id
                        );
                    }
                }
            }
            jdbc.update("UPDATE lesson SET status = 'PUBLISHED' WHERE id = ?", lessonId);
            jdbc.update("UPDATE lesson_authoring SET published_at = now() WHERE lesson_id = ?", lessonId);
        }
        return lessonId;
    }

    private UUID activityId(UUID lessonId, String type) {
        return jdbc.queryForObject(
            "SELECT id FROM lesson_activity WHERE lesson_id = ? AND activity_type = ?",
            UUID.class, lessonId, type
        );
    }

    private List<UUID> sentenceIds(UUID lessonId) {
        return jdbc.queryForList(
            "SELECT id FROM lesson_sentence WHERE lesson_id = ? ORDER BY position",
            UUID.class, lessonId
        );
    }
}
