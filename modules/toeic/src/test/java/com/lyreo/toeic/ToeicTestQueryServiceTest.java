package com.lyreo.toeic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.toeic.api.ToeicAttemptController.ToeicTestResponse;
import com.lyreo.toeic.application.ToeicTestQueryService;
import com.lyreo.toeic.application.port.ToeicAttemptRepository;
import com.lyreo.toeic.application.port.ToeicTestContentRepository;
import com.lyreo.toeic.domain.ToeicTestContent;
import com.lyreo.toeic.domain.ToeicTestContent.Document;
import com.lyreo.toeic.domain.ToeicTestContent.Item;
import com.lyreo.toeic.domain.ToeicTestContent.MediaAsset;
import com.lyreo.toeic.domain.ToeicTestContent.Option;
import com.lyreo.toeic.domain.ToeicTestContent.Placement;
import com.lyreo.toeic.domain.ToeicTestContent.StimulusGroup;
import com.lyreo.toeic.domain.ToeicTestContent.TestMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

class ToeicTestQueryServiceTest {
    @Test
    void returnsPublishedActiveVersionHierarchyAndOmitsAnswerKeyAndSourceUrls() {
        UUID learnerId = UUID.randomUUID();
        UUID catalogId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        ToeicTestContent snapshot = snapshot(catalogId, versionId);
        FakeAttempts attempts = new FakeAttempts(new ToeicAttemptRepository.TestAccess(
            catalogId, versionId, "PUBLISHED", "PUBLIC", null
        ));
        FakeContent content = new FakeContent(snapshot);
        var service = new ToeicTestQueryService(attempts, content, new FakeEntitlements(Set.of()));

        ToeicTestContent result = service.getActiveTest(learnerId, catalogId);

        assertThat(result.testVersionId()).isEqualTo(versionId);
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.documents()).singleElement().extracting(Document::html).isEqualTo("<p>Passage</p>");
            assertThat(group.media()).singleElement().extracting(MediaAsset::storageObjectKey)
                .isEqualTo("media/toeic/audio/sha256.ogg");
        });
        assertThat(result.placements()).singleElement().satisfies(placement -> {
            assertThat(placement.item().options()).extracting(Option::key).containsExactly("A", "B", "C", "D");
            assertThat(placement.item().media()).singleElement().extracting(MediaAsset::storageObjectKey)
                .isEqualTo("media/toeic/image/sha256.png");
        });
        assertThat(content.requestedCatalogId).isEqualTo(catalogId);
        assertThat(content.requestedVersionId).isEqualTo(versionId);

        String json = new ObjectMapper().writeValueAsString(ToeicTestResponse.from(result));
        assertThat(json)
            .contains("storageObjectKey")
            .doesNotContain("correctOption")
            .doesNotContain("correct_answer")
            .doesNotContain("transcriptEn")
            .doesNotContain("contentTranslationVi")
            .doesNotContain("vocabularyNoteVi")
            .doesNotContain("sourceReference");
    }

    @Test
    void hidesUnpublishedCatalogsBeforeReadingContent() {
        UUID catalogId = UUID.randomUUID();
        FakeContent content = new FakeContent(null);
        var service = new ToeicTestQueryService(
            new FakeAttempts(new ToeicAttemptRepository.TestAccess(
                catalogId, UUID.randomUUID(), "HIDDEN", "PUBLIC", null
            )),
            content,
            new FakeEntitlements(Set.of())
        );

        assertThatThrownBy(() -> service.getActiveTest(UUID.randomUUID(), catalogId))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(content.requestedCatalogId).isNull();
    }

    @Test
    void requiresTheCatalogFeatureBeforeReadingFeatureRestrictedContent() {
        UUID catalogId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();
        FakeContent content = new FakeContent(snapshot(catalogId, versionId));
        FakeEntitlements entitlements = new FakeEntitlements(Set.of());
        var service = new ToeicTestQueryService(
            new FakeAttempts(new ToeicAttemptRepository.TestAccess(
                catalogId, versionId, "PUBLISHED", "FEATURE", "toeic.full-access"
            )),
            content,
            entitlements
        );

        assertThatThrownBy(() -> service.getActiveTest(learnerId, catalogId))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(entitlements.checkedFeatures).containsExactly("toeic.full-access");
        assertThat(content.requestedCatalogId).isNull();

        entitlements.grants.add("toeic.full-access");
        assertThat(service.getActiveTest(learnerId, catalogId).testVersionId()).isEqualTo(versionId);
    }

    private static ToeicTestContent snapshot(UUID catalogId, UUID versionId) {
        UUID groupId = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        return new ToeicTestContent(
            catalogId,
            versionId,
            new TestMetadata("Practice Test", 2024, 1, UUID.randomUUID(), 1, "Source", 2700, 4500, 200, 3, 1),
            List.of(new StimulusGroup(
                groupId,
                7,
                "TEXT",
                "Questions 1-2",
                1,
                2,
                List.of(1, 2),
                "<p>Passage</p>",
                "SINGLE",
                1,
                List.of(new Document(UUID.randomUUID(), 1, "article", "<p>Passage</p>", "SINGLE")),
                List.of(new MediaAsset("audio-hash", "audio", 1, "media/toeic/audio/sha256.ogg", "audio/ogg", 1000))
            )),
            List.of(new Placement(
                placementId,
                groupId,
                "reading",
                7,
                1,
                null,
                1,
                new Item(
                    itemId,
                    "MULTIPLE_CHOICE",
                    "What is stated?",
                    List.of(new Option("A", "One"), new Option("B", "Two"), new Option("C", "Three"), new Option("D", "Four")),
                    3,
                    List.of(new MediaAsset("image-hash", "image", null, "media/toeic/image/sha256.png", "image/png", 250))
                )
            ))
        );
    }

    private static final class FakeAttempts implements ToeicAttemptRepository {
        private final TestAccess test;

        private FakeAttempts(TestAccess test) {
            this.test = test;
        }

        @Override
        public Optional<TestAccess> findActiveTest(UUID catalogId) {
            return test.catalogId().equals(catalogId) ? Optional.of(test) : Optional.empty();
        }

        @Override
        public List<QuestionKey> answerKey(UUID testVersionId, Set<UUID> placementIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UUID saveCompletedAttempt(UUID learnerId, UUID testVersionId, String mode, ScoreSummary score,
                                        Map<UUID, String> submittedAnswers, List<QuestionKey> answerKey) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeContent implements ToeicTestContentRepository {
        private final ToeicTestContent snapshot;
        private UUID requestedCatalogId;
        private UUID requestedVersionId;

        private FakeContent(ToeicTestContent snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<ToeicTestContent> findActiveTestContent(UUID catalogId, UUID testVersionId) {
            requestedCatalogId = catalogId;
            requestedVersionId = testVersionId;
            if (snapshot == null || !snapshot.catalogId().equals(catalogId)
                || !snapshot.testVersionId().equals(testVersionId)) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        }
    }

    private static final class FakeEntitlements implements EntitlementService {
        private final Set<String> grants;
        private final List<String> checkedFeatures = new ArrayList<>();

        private FakeEntitlements(Set<String> grants) {
            this.grants = new HashSet<>(grants);
        }

        @Override
        public boolean hasFeature(UUID userId, FeatureKey featureKey) {
            return grants.contains(featureKey.value());
        }

        @Override
        public void requireFeature(UUID userId, FeatureKey featureKey) {
            checkedFeatures.add(featureKey.value());
            if (!hasFeature(userId, featureKey)) throw new AccessDeniedException("No grant");
        }
    }
}
