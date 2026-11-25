package com.lyreo.toeic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.assessment.api.AssessmentSessionController;
import com.lyreo.assessment.api.AssessmentSessions;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.SessionExpiredException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.grammar.GrammarSessionCompletedEvent;
import com.lyreo.entitlement.api.FeatureEntitlementRequiredException;
import com.lyreo.grammar.api.GrammarCatalogController;
import com.lyreo.grammar.application.PublishedGrammarCatalogs;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.identity.application.ProvisionedUser;
import com.lyreo.platform.content.AiDraftRequests;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.EditorialFiles;
import com.lyreo.platform.content.QualityReports;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.toeic.api.ToeicCatalogController;
import com.lyreo.toeic.application.PublishedToeicTests;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = GrammarToeicPracticeIT.Config.class)
class GrammarToeicPracticeIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void flywayLocations(DynamicPropertyRegistry registry) {
        Path migrations = Path.of("../../apps/core-service/src/main/resources/db/migration").toAbsolutePath().normalize();
        if (!Files.exists(migrations)) {
            migrations = Path.of("apps/core-service/src/main/resources/db/migration").toAbsolutePath().normalize();
        }
        String location = "filesystem:" + migrations;
        registry.add("spring.flyway.locations", () -> location);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(
        basePackages = {
            "com.lyreo.platform.config",
            "com.lyreo.platform.content",
            "com.lyreo.entitlement",
            "com.lyreo.assessment",
            "com.lyreo.grammar",
            "com.lyreo.toeic"
        },
        excludeFilters = @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {
                AiDraftRequests.class,
                EditorialFiles.class,
                QualityReports.class
            }
        )
    )
    static class Config {
        @Bean
        Events events() {
            return new Events();
        }

        @Bean
        AppUserProvisioningService appUserProvisioningService(JdbcTemplate jdbc) {
            AppUserProvisioningService service = mock(AppUserProvisioningService.class);
            when(service.provision(anyString(), any())).thenAnswer(invocation -> {
                String subject = invocation.getArgument(0);
                String email = invocation.getArgument(1);
                UUID existing = jdbc.query(
                    "SELECT id FROM app_user WHERE keycloak_subject = ?",
                    (rs, n) -> rs.getObject(1, UUID.class),
                    subject
                ).stream().findFirst().orElse(null);
                if (existing != null) {
                    return new ProvisionedUser(existing, subject, email, Instant.now());
                }
                UUID created = UUID.randomUUID();
                jdbc.update(
                    "INSERT INTO app_user(id, keycloak_subject, email_snapshot) VALUES (?, ?, ?)",
                    created, subject, email
                );
                return new ProvisionedUser(created, subject, email, Instant.now());
            });
            return service;
        }

        @Bean
        ObjectStoragePort objectStoragePort() {
            return new ObjectStoragePort() {
                @Override
                public StoredObject put(String objectKey, String contentType, byte[] content) {
                    return new StoredObject(objectKey, content.length, "etag");
                }

                @Override
                public InputStream get(String objectKey) {
                    return new ByteArrayInputStream("audio-bytes".getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public boolean exists(String objectKey) {
                    return true;
                }

                @Override
                public void delete(String objectKey) {}

                @Override
                public URI createDownloadUrl(String objectKey, Duration ttl) {
                    return URI.create("http://localhost/" + objectKey);
                }
            };
        }

        @Bean
        AiInvocationService aiInvocationService() {
            return mock(AiInvocationService.class);
        }

        @Bean
        AiDraftRequests aiDraftRequests() {
            return mock(AiDraftRequests.class);
        }

        @Bean
        QualityReports qualityReports() {
            return mock(QualityReports.class);
        }

        @Bean
        BackgroundJobService backgroundJobService() {
            return mock(BackgroundJobService.class);
        }

        @Bean
        EditorialFiles editorialFiles() {
            return mock(EditorialFiles.class);
        }
    }

    static class Events {
        final List<GrammarQuestionAnsweredEvent> grammarAnswers = new CopyOnWriteArrayList<>();
        final List<GrammarSessionCompletedEvent> grammarCompleted = new CopyOnWriteArrayList<>();

        @EventListener
        void onGrammarAnswered(GrammarQuestionAnsweredEvent event) {
            grammarAnswers.add(event);
        }

        @EventListener
        void onGrammarCompleted(GrammarSessionCompletedEvent event) {
            grammarCompleted.add(event);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Events events;
    @Autowired GrammarCatalogController grammarController;
    @Autowired PublishedGrammarCatalogs grammarCatalogs;
    @Autowired ToeicCatalogController toeicController;
    @Autowired PublishedToeicTests toeicTests;
    @Autowired AssessmentSessionController sessionController;
    @Autowired AssessmentSessions sessions;
    @Autowired AppUserProvisioningService users;

    private Jwt jwtFor(String subject) {
        return Jwt.withTokenValue("token-" + subject)
            .header("alg", "none")
            .subject(subject)
            .claim("email", subject + "@example.test")
            .build();
    }

    private UUID createDraftRelease(String tag) {
        UUID release = UUID.randomUUID();
        String checksum = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("""
            INSERT INTO dataset_release(id, domain, package_version, schema_version, checksum_sha256, manifest_json)
            VALUES (?, 'grammar-toeic', ?, '1.0.0', ?, '{}'::jsonb)
            """, release, "test-" + tag, checksum);
        return release;
    }

    private void activateRelease(UUID release) {
        jdbc.update("""
            INSERT INTO dataset_import_run(id, release_id, run_type, status, completed_at)
            VALUES (?, ?, 'APPLY', 'SUCCEEDED', now())
            """, UUID.randomUUID(), release);
        jdbc.update("""
            INSERT INTO dataset_active_release(domain, release_id, activated_by)
            VALUES ('grammar-toeic', ?, 'test')
            ON CONFLICT (domain) DO UPDATE SET release_id = excluded.release_id, activated_by = 'test'
            """, release);
    }

    private UUID insertAssessmentItem(UUID release, String stem, String kind, String correctOption) {
        UUID itemId = UUID.randomUUID();
        String optionsJson = """
            [
              {"key":"A","text":"Option A"},
              {"key":"B","text":"Option B"},
              {"key":"C","text":"Option C"},
              {"key":"D","text":"Option D"}
            ]
            """;
        jdbc.update("""
            INSERT INTO assessment_item(
                id, release_id, package_item_id, kind, stem_en, options, correct_option, difficulty_level
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 2)
            """, itemId, release, UUID.randomUUID(), kind, stem, optionsJson, correctOption);
        return itemId;
    }

    private void grantFeature(UUID userId, String featureKey) {
        jdbc.update("""
            INSERT INTO user_entitlement_grant(
                id, user_id, feature_key, status, valid_from, valid_until, source_type, created_by
            ) VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour', now() + interval '1 day', 'MANUAL_ADMIN', 'test')
            """, UUID.randomUUID(), userId, featureKey);
    }

    @Test
    void toeicPartBreakdownLockedDiscoveryForbidsStartAndSupportsPartDrillWithIdempotency() throws Exception {
        UUID release = createDraftRelease("toeic-" + UUID.randomUUID().toString().substring(0, 8));
        UUID releaseAssetId = UUID.randomUUID();
        String blobHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String mediaKey = "media/sha256/" + blobHash.substring(0, 2) + "/" + blobHash;
        jdbc.update("""
            INSERT INTO content_asset(id, sha256, storage_key, mime_type, size_bytes, filename, created_by)
            VALUES (?, ?, ?, 'audio/mpeg', 11, 'part3.mp3', 'test')
            """, releaseAssetId, blobHash, mediaKey);
        jdbc.update("""
            INSERT INTO media_blob(sha256, mime_type, size_bytes, storage_object_key)
            VALUES (?, 'audio/mpeg', 11, ?)
            """, blobHash, mediaKey);
        jdbc.update("""
            INSERT INTO release_media_asset(id, release_id, package_asset_id, blob_sha256, package_path, source_paths)
            VALUES (?, ?, ?, ?, 'media/part3.mp3', '[]'::jsonb)
            """, releaseAssetId, release, blobHash, blobHash);

        UUID lockedTestId = UUID.randomUUID();
        UUID lockedVersionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO toeic_test_catalog(id, year, test_number, publication_status, access_mode, required_feature_key, reconciliation_status)
            VALUES (?, 2026, 91, 'PUBLISHED', 'FEATURE', 'toeic.full-access', 'CONFIRMED')
            """, lockedTestId);
        UUID setId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO toeic_test_version(
                id, release_id, catalog_id, package_test_id, name, year, test_number, set_id,
                listening_duration_seconds, reading_duration_seconds, total_questions, source_is_free, source_is_hidden
            ) VALUES (?, ?, ?, ?, 'ETS 2026 Plus Test 1', 2026, 91, ?, 2700, 4500, 200, false, false)
            """, lockedVersionId, release, lockedTestId, UUID.randomUUID(), setId);

        int[] partCounts = {0, 6, 25, 39, 30, 30, 16, 54};
        int qNum = 0;
        for (int part = 1; part <= 7; part++) {
            UUID currentGroup = null;
            int groupSize = part == 6 ? 4 : 3;
            for (int i = 0; i < partCounts[part]; i++) {
                boolean needsGroup = Set.of(3, 4, 6, 7).contains(part);
                if (needsGroup && (part == 7
                    ? Set.of(0, 2, 4, 6, 8, 11, 14, 17, 21, 25, 29, 34, 39, 44, 49).contains(i)
                    : i % groupSize == 0)) {
                    currentGroup = UUID.randomUUID();
                    jdbc.update("""
                        INSERT INTO toeic_stimulus_group(
                            id, release_id, test_version_id, package_group_id, part, kind, order_index,
                            question_numbers, document_parse_status, document_count, source_issues, render_html
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ARRAY[?]::integer[], 'PARSED', ?, '[]'::jsonb, ?)
                        """, currentGroup, release, lockedVersionId, UUID.randomUUID(), part,
                        part <= 4 ? "AUDIO" : "TEXT", i + 1, qNum + 1, part >= 6 ? 1 : 0, part >= 6 ? "<p>Passage</p>" : null);
                    if (part == 3 && i == 0) {
                        jdbc.update("""
                            INSERT INTO media_asset_use(id, release_id, package_use_id, asset_id, role, group_id)
                            VALUES (?, ?, ?, ?, 'audio', ?)
                            """, UUID.randomUUID(), release, "use:" + currentGroup, blobHash, currentGroup);
                    }
                }
                UUID itemId = insertAssessmentItem(release, part == 6 ? null : "TOEIC Q" + (qNum + 1),
                    part == 6 ? "CLOZE" : "MCQ_SINGLE", "A");
                ++qNum;
                jdbc.update("""
                    INSERT INTO toeic_placement(
                        id, release_id, test_version_id, package_placement_id, item_id, group_id, section, part, question_number, gap_number, order_index
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), release, lockedVersionId, UUID.randomUUID(), itemId, currentGroup,
                    part <= 4 ? "listening" : "reading", part, qNum, part == 6 ? qNum : null, qNum);
            }
        }

        UUID draftTestId = UUID.randomUUID();
        UUID draftVersionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO toeic_test_catalog(id, year, test_number, publication_status, access_mode, required_feature_key, reconciliation_status)
            VALUES (?, 2026, 92, 'DRAFT', 'PUBLIC', NULL, 'CONFIRMED')
            """, draftTestId);
        jdbc.update("""
            INSERT INTO toeic_test_version(
                id, release_id, catalog_id, package_test_id, name, year, test_number, set_id,
                listening_duration_seconds, reading_duration_seconds, total_questions, source_is_free, source_is_hidden
            ) VALUES (?, ?, ?, ?, 'ETS 2026 Draft Test', 2026, 92, ?, 2700, 4500, 200, true, false)
            """, draftVersionId, release, draftTestId, UUID.randomUUID(), UUID.randomUUID());

        activateRelease(release);

        Jwt freeJwt = jwtFor("toeic-free-" + UUID.randomUUID());
        Jwt otherJwt = jwtFor("toeic-other-" + UUID.randomUUID());

        var page = toeicController.page(freeJwt, 0, 20);
        assertThat(page.items()).extracting(PublishedToeicTests.Catalog::id).contains(lockedTestId).doesNotContain(draftTestId);
        var lockedItem = page.items().stream().filter(c -> c.id().equals(lockedTestId)).findFirst().orElseThrow();
        assertThat(lockedItem.accessMode()).isEqualTo("FEATURE");
        assertThat(lockedItem.locked()).isTrue();
        assertThat(lockedItem.requiredFeatureKey()).isEqualTo("toeic.full-access");
        assertThat(lockedItem.lockReason()).isEqualTo("FEATURE_GRANT_REQUIRED");

        var detail = toeicController.detail(freeJwt, lockedTestId);
        assertThat(detail.locked()).isTrue();
        assertThat(detail.parts()).hasSize(7);
        assertThat(detail.parts().get(0).section()).isEqualTo("LISTENING");
        assertThat(detail.parts().get(4).part()).isEqualTo(5);
        assertThat(detail.parts().get(4).section()).isEqualTo("READING");
        assertThat(detail.parts().get(4).questionCount()).isEqualTo(30);
        assertThat(detail.parts().get(4).groupCount()).isZero();
        assertThat(detail.parts().get(5).part()).isEqualTo(6);
        assertThat(detail.parts().get(5).questionCount()).isEqualTo(16);
        assertThat(detail.parts().get(5).groupCount()).isEqualTo(4);
        assertThat(detail.parts().get(5).placementIds()).hasSize(16);

        assertThatThrownBy(() -> toeicController.start(
            freeJwt, lockedTestId, null, new ToeicCatalogController.Start("DRILL", null, List.of(5, 6))
        )).isInstanceOf(FeatureEntitlementRequiredException.class);

        UUID freeLearnerId = users.provision(freeJwt.getSubject(), freeJwt.getClaimAsString("email")).id();
        grantFeature(freeLearnerId, "toeic.full-access");

        var unlockedDetail = toeicController.detail(freeJwt, lockedTestId);
        assertThat(unlockedDetail.locked()).isFalse();
        assertThat(unlockedDetail.lockReason()).isNull();

        String idemKey = "toeic-drill-" + UUID.randomUUID();
        var started = toeicController.start(
            freeJwt, lockedTestId, idemKey, new ToeicCatalogController.Start("DRILL", null, List.of(5, 6))
        ).getBody();
        assertThat(started).isNotNull();
        assertThat(started.mode()).isEqualTo("DRILL");
        assertThat(started.questions()).hasSize(46);
        assertThat(started.questions()).extracting(AssessmentSessions.QuestionView::part).containsOnly(5, 6);

        var replayed = toeicController.start(
            freeJwt, lockedTestId, idemKey, new ToeicCatalogController.Start("DRILL", null, List.of(5, 6))
        ).getBody();
        assertThat(replayed).isNotNull();
        assertThat(replayed.id()).isEqualTo(started.id());

        assertThatThrownBy(() -> toeicController.start(
            freeJwt, lockedTestId, idemKey, new ToeicCatalogController.Start("DRILL", null, List.of(5))
        )).isInstanceOf(IdempotencyConflictException.class);

        // Start Part 3 drill and verify owner media 200 vs cross-learner media 404
        var mediaSession = toeicController.start(
            freeJwt, lockedTestId, null, new ToeicCatalogController.Start("DRILL", null, List.of(3))
        ).getBody();
        assertThat(mediaSession).isNotNull();
        UUID groupAudioId = mediaSession.groups().stream()
            .flatMap(g -> g.media().stream())
            .map(QuestionContent.Media::assetId)
            .findFirst().orElseThrow();
        assertThat(sessionController.media(freeJwt, mediaSession.id(), groupAudioId).getStatusCode().value()).isEqualTo(200);
        assertThatThrownBy(() -> sessionController.media(otherJwt, mediaSession.id(), groupAudioId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void grammarPublishedPaginationParentFilterLockMetadataIdempotencyAndEvents() {
        UUID release = createDraftRelease("grammar-" + UUID.randomUUID().toString().substring(0, 8));
        UUID item1 = insertAssessmentItem(release, "She ___ to work.", "MCQ_SINGLE", "A");
        UUID item2 = insertAssessmentItem(release, "They ___ here.", "MCQ_SINGLE", "B");

        UUID topicOpen = UUID.randomUUID(), topicOpenVer = UUID.randomUUID();
        UUID topicLocked = UUID.randomUUID(), topicLockedVer = UUID.randomUUID();
        UUID topicHidden = UUID.randomUUID(), topicHiddenVer = UUID.randomUUID();

        for (var spec : List.of(
            new Object[]{topicOpen, topicOpenVer, "PUBLISHED", "PUBLIC", null, 1, "Tenses Open"},
            new Object[]{topicLocked, topicLockedVer, "PUBLISHED", "FEATURE", "grammar.advanced", 2, "Tenses Plus"},
            new Object[]{topicHidden, topicHiddenVer, "HIDDEN", "PUBLIC", null, 3, "Tenses Hidden"}
        )) {
            UUID cid = (UUID) spec[0];
            UUID vid = (UUID) spec[1];
            jdbc.update("""
                INSERT INTO grammar_topic_catalog(id, publication_status, access_mode, required_feature_key, reconciliation_status)
                VALUES (?, ?, ?, ?, 'CONFIRMED')
                """, cid, spec[2], spec[3], spec[4]);
            jdbc.update("""
                INSERT INTO grammar_topic_version(
                    id, release_id, catalog_id, package_topic_id, title_en, title_vi, order_index, source_access_level, source_is_hidden
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'FREE', false)
                """, vid, release, cid, UUID.randomUUID(), spec[6], spec[6], spec[5]);
        }

        UUID sub1 = UUID.randomUUID(), sub1Ver = UUID.randomUUID();
        UUID sub2 = UUID.randomUUID(), sub2Ver = UUID.randomUUID();
        UUID subDraft = UUID.randomUUID(), subDraftVer = UUID.randomUUID();
        UUID subUnderLocked = UUID.randomUUID(), subUnderLockedVer = UUID.randomUUID();
        UUID subUnderHidden = UUID.randomUUID(), subUnderHiddenVer = UUID.randomUUID();

        for (var spec : List.of(
            new Object[]{sub1, sub1Ver, topicOpen, topicOpenVer, "PUBLISHED", "PUBLIC", 1, "Present Simple"},
            new Object[]{sub2, sub2Ver, topicOpen, topicOpenVer, "PUBLISHED", "PUBLIC", 2, "Past Simple"},
            new Object[]{subDraft, subDraftVer, topicOpen, topicOpenVer, "DRAFT", "PUBLIC", 3, "Future Draft"},
            new Object[]{subUnderLocked, subUnderLockedVer, topicLocked, topicLockedVer, "PUBLISHED", "PUBLIC", 4, "Advanced Inversion"},
            new Object[]{subUnderHidden, subUnderHiddenVer, topicHidden, topicHiddenVer, "PUBLISHED", "PUBLIC", 5, "Orphaned Published Subtopic"}
        )) {
            UUID sid = (UUID) spec[0];
            UUID svid = (UUID) spec[1];
            UUID tid = (UUID) spec[2];
            UUID tvid = (UUID) spec[3];
            jdbc.update("""
                INSERT INTO grammar_subtopic_catalog(id, topic_catalog_id, publication_status, access_mode, required_feature_key, reconciliation_status)
                VALUES (?, ?, ?, ?, NULL, 'CONFIRMED')
                """, sid, tid, spec[4], spec[5]);
            jdbc.update("""
                INSERT INTO grammar_subtopic_version(
                    id, release_id, catalog_id, topic_catalog_id, package_subtopic_id, package_topic_id, topic_version_id,
                    title_en, title_vi, order_index, source_access_level, source_is_hidden
                ) VALUES (?, ?, ?, ?, ?, (SELECT package_topic_id FROM grammar_topic_version WHERE id = ?), ?, ?, ?, ?, 'FREE', false)
                """, svid, release, sid, tid, UUID.randomUUID(), tvid, tvid, spec[7], spec[7], spec[6]);
            jdbc.update("""
                INSERT INTO grammar_membership(
                    id, release_id, package_membership_id, item_id, mode, topic_version_id, subtopic_version_id, order_index, source_url
                )
                VALUES (?, ?, ?, ?, 'topic', ?, ?, 1, 'https://example.test/grammar/1'),
                       (?, ?, ?, ?, 'topic', ?, ?, 2, 'https://example.test/grammar/2')
                """, UUID.randomUUID(), release, "m1:" + svid, item1, tvid, svid,
                UUID.randomUUID(), release, "m2:" + svid, item2, tvid, svid);
        }

        activateRelease(release);

        Jwt freeJwt = jwtFor("grammar-free-" + UUID.randomUUID());

        var topicPage = grammarController.page(freeJwt, "topic", null, 0, 1);
        assertThat(topicPage.items()).hasSize(1);
        assertThat(topicPage.total()).isGreaterThanOrEqualTo(2);

        var filteredSubs = grammarController.list(freeJwt, "subtopic", topicOpen, 0, 20);
        assertThat(filteredSubs).extracting(PublishedGrammarCatalogs.Catalog::id)
            .containsExactly(sub1, sub2)
            .doesNotContain(subDraft, subUnderHidden);

        var allPublishedSubs = grammarController.page(freeJwt, "subtopic", null, 0, 50);
        assertThat(allPublishedSubs.items()).extracting(PublishedGrammarCatalogs.Catalog::id)
            .contains(sub1, sub2, subUnderLocked)
            .doesNotContain(subDraft, subUnderHidden);
        assertThat(allPublishedSubs.total()).isEqualTo(allPublishedSubs.items().size());

        var lockedTopicDetail = grammarController.detail(freeJwt, "topic", topicLocked);
        assertThat(lockedTopicDetail.accessMode()).isEqualTo("FEATURE");
        assertThat(lockedTopicDetail.locked()).isTrue();
        assertThat(lockedTopicDetail.requiredFeatureKey()).isEqualTo("grammar.advanced");
        assertThat(lockedTopicDetail.lockReason()).isEqualTo("FEATURE_GRANT_REQUIRED");

        var lockedSubDetail = grammarController.detail(freeJwt, "subtopic", subUnderLocked);
        assertThat(lockedSubDetail.locked()).isTrue();
        assertThat(lockedSubDetail.requiredFeatureKey()).isEqualTo("grammar.advanced");

        assertThatThrownBy(() -> grammarController.start(freeJwt, "topic", topicLocked, null, new GrammarCatalogController.Start(5, null)))
            .isInstanceOf(FeatureEntitlementRequiredException.class);
        assertThatThrownBy(() -> grammarController.start(freeJwt, "subtopic", subUnderLocked, null, new GrammarCatalogController.Start(5, null)))
            .isInstanceOf(FeatureEntitlementRequiredException.class);

        String idemKey = "grammar-start-" + UUID.randomUUID();
        var started = grammarController.start(freeJwt, "subtopic", sub1, idemKey, new GrammarCatalogController.Start(2, null)).getBody();
        assertThat(started).isNotNull();
        assertThat(started.questions()).hasSize(2);
        var replayed = grammarController.start(freeJwt, "subtopic", sub1, idemKey, new GrammarCatalogController.Start(2, null)).getBody();
        assertThat(replayed).isNotNull();
        assertThat(replayed.id()).isEqualTo(started.id());
        assertThatThrownBy(() -> grammarController.start(freeJwt, "subtopic", sub1, idemKey, new GrammarCatalogController.Start(1, null)))
            .isInstanceOf(IdempotencyConflictException.class);

        UUID q1SessionItemId = started.questions().get(0).id();
        UUID q2SessionItemId = started.questions().get(1).id();
        var saved = sessionController.save(freeJwt, started.id(), new AssessmentSessionController.Answers(
            started.revision(),
            Map.of(q1SessionItemId, "A", q2SessionItemId, "A")
        ));
        var submitted = sessionController.submit(freeJwt, started.id(), new AssessmentSessionController.Submit(saved.revision()));
        assertThat(submitted.result().correct()).isEqualTo(1);
        assertThat(submitted.result().total()).isEqualTo(2);

        UUID learnerId = users.provision(freeJwt.getSubject(), freeJwt.getClaimAsString("email")).id();
        assertThat(events.grammarAnswers.stream().filter(e -> e.learnerId().equals(learnerId))).hasSize(2);
        assertThat(events.grammarCompleted.stream().filter(e -> e.sessionId().equals(started.id())))
            .singleElement()
            .satisfies(ev -> {
                assertThat(ev.learnerId()).isEqualTo(learnerId);
                assertThat(ev.catalogId()).isEqualTo(sub1);
                assertThat(ev.catalogKind()).isEqualTo("PRACTICE");
                assertThat(ev.correctCount()).isEqualTo(1);
                assertThat(ev.totalCount()).isEqualTo(2);
            });
    }

    @Test
    void sessionResumeLookupStaleRevisionExpiredAndConcurrentSubmitIdempotency() throws Exception {
        Jwt learnerJwt = jwtFor("resume-" + UUID.randomUUID());
        UUID learnerId = users.provision(learnerJwt.getSubject(), learnerJwt.getClaimAsString("email")).id();
        UUID catalogA = UUID.randomUUID();
        UUID catalogB = UUID.randomUUID();
        UUID itemA = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        var content = new QuestionContent(
            "Choose the best option",
            "MCQ_SINGLE",
            List.of(new QuestionContent.Option("A", "One"), new QuestionContent.Option("B", "Two")),
            "A", 2, "Explanation", null, null, null, null, List.of()
        );
        var snapA = new AssessmentSessions.Snapshot("Grammar A", null, List.of(
            new AssessmentSessions.Item(UUID.randomUUID(), itemA, null, 0, 1, catalogA, null, null, content, null)
        ), List.of());
        var snapB = new AssessmentSessions.Snapshot("Grammar B", null, List.of(
            new AssessmentSessions.Item(UUID.randomUUID(), itemB, null, 0, 1, catalogB, null, null, content, null)
        ), List.of());

        var openA = sessions.start(learnerId, "grammar", catalogA, "PRACTICE", snapA);
        var submittedB = sessions.start(learnerId, "grammar", catalogB, "PRACTICE", snapB);
        sessions.submit(learnerId, submittedB.id(), submittedB.revision());

        UUID expiredC = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO assessment_session(id, learner_id, domain, catalog_id, mode, snapshot, created_at, expires_at)
            VALUES (?, ?, 'grammar', ?, 'PRACTICE', CAST(? AS jsonb), now() - interval '25 hours', now() - interval '1 hour')
            """, expiredC, learnerId, catalogA, json.writeValueAsString(snapA));

        assertThat(sessionController.history(learnerJwt, "grammar", "OPEN", catalogA, 0, 20))
            .extracting(AssessmentSessions.History::id).containsExactly(openA.id());
        assertThat(sessionController.history(learnerJwt, "grammar", "SUBMITTED", null, 0, 20))
            .extracting(AssessmentSessions.History::id).containsExactly(submittedB.id());
        assertThat(sessionController.history(learnerJwt, "grammar", "EXPIRED", catalogA, 0, 20))
            .extracting(AssessmentSessions.History::id).containsExactly(expiredC);

        // StaleRevisionException on save & submit
        var savedA = sessions.save(learnerId, openA.id(), openA.revision(), Map.of(openA.questions().getFirst().id(), "A"));
        assertThatThrownBy(() -> sessions.save(learnerId, openA.id(), openA.revision(), Map.of(openA.questions().getFirst().id(), "B")))
            .isInstanceOf(StaleRevisionException.class)
            .satisfies(ex -> assertThat(((StaleRevisionException) ex).currentRevision()).isEqualTo(savedA.revision()));
        assertThatThrownBy(() -> sessions.submit(learnerId, openA.id(), openA.revision()))
            .isInstanceOf(StaleRevisionException.class)
            .satisfies(ex -> assertThat(((StaleRevisionException) ex).currentRevision()).isEqualTo(savedA.revision()));

        // SessionExpiredException on unsubmitted expired session; already submitted remains idempotent
        assertThatThrownBy(() -> sessions.save(learnerId, expiredC, 1L, Map.of()))
            .isInstanceOf(SessionExpiredException.class)
            .satisfies(ex -> assertThat(((SessionExpiredException) ex).expiredAt()).isNotNull());
        assertThatThrownBy(() -> sessions.submit(learnerId, expiredC, 1L))
            .isInstanceOf(SessionExpiredException.class)
            .satisfies(ex -> assertThat(((SessionExpiredException) ex).expiredAt()).isNotNull());
        assertThat(sessions.submit(learnerId, submittedB.id(), submittedB.revision()).status()).isEqualTo("SUBMITTED");

        // Concurrent submit idempotency on openA
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var f1 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return sessions.submit(learnerId, openA.id(), savedA.revision());
            });
            var f2 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return sessions.submit(learnerId, openA.id(), savedA.revision());
            });
            ready.await();
            go.countDown();
            assertThat(f1.get().result()).isEqualTo(f2.get().result());
        }
        assertThat(events.grammarCompleted.stream().filter(e -> e.sessionId().equals(openA.id()))).hasSize(1);
        assertThat(events.grammarAnswers.stream().filter(e -> e.learnerId().equals(learnerId) && e.itemId().equals(itemA))).hasSize(1);
    }
}
