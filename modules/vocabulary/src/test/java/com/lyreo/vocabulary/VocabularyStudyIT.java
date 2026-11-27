package com.lyreo.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.SessionExpiredException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.entitlement.api.FeatureEntitlementRequiredException;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.vocabulary.application.VocabularyStudyService;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.domain.PracticeMode;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

class VocabularyStudyIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate namedJdbc;
    private static PlatformTransactionManager txManager;
    private static ObjectMapper mapper;
    private static InMemoryStorage storage;

    private AnnotationConfigApplicationContext context;
    private List<VocabularyUnitCompletedEvent> unitEvents;
    private List<VocabularyReviewCompletedEvent> reviewEvents;
    private VocabularyStudyService studyService;

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {}

    @BeforeAll
    static void startDb() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Path migrations = Path.of("../../apps/core-service/src/main/resources/db/migration")
            .toAbsolutePath().normalize();
        Flyway.configure().dataSource(dataSource).locations("filesystem:" + migrations).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        txManager = new JdbcTransactionManager(dataSource);
        mapper = new ObjectMapper();
        storage = new InMemoryStorage();
    }

    @BeforeEach
    void wireServices() {
        unitEvents = new CopyOnWriteArrayList<>();
        reviewEvents = new CopyOnWriteArrayList<>();

        context = new AnnotationConfigApplicationContext();
        context.register(TxConfig.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> namedJdbc);
        context.registerBean(PlatformTransactionManager.class, () -> txManager);
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(ObjectStoragePort.class, () -> storage);
        context.registerBean(DocumentStore.class);
        context.registerBean(AssetStore.class);
        context.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload) {
                if (payload.getPayload() instanceof VocabularyUnitCompletedEvent u) {
                    unitEvents.add(u);
                } else if (payload.getPayload() instanceof VocabularyReviewCompletedEvent r) {
                    reviewEvents.add(r);
                }
            }
        });
        context.scan(
            "com.lyreo.platform.config.infrastructure",
            "com.lyreo.entitlement.infrastructure",
            "com.lyreo.lexicon.infrastructure",
            "com.lyreo.vocabulary.infrastructure"
        );
        context.refresh();

        studyService = context.getBean(VocabularyStudyService.class);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void supportsCatalogEntitlementMediaAllSixModesAndIdempotentSubmitWithoutAlteringSrs() throws Exception {
        UUID learnerA = UUID.randomUUID();
        UUID learnerB = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learnerA, "sub-a-" + learnerA);
        jdbc.update("INSERT INTO app_user(id, keycloak_subject) VALUES (?, ?)", learnerB, "sub-b-" + learnerB);

        UUID releaseId = UUID.randomUUID();
        String checksum = "a".repeat(64);
        jdbc.update("""
            INSERT INTO dataset_release(id, domain, package_version, schema_version, checksum_sha256, manifest_json)
            VALUES (?, 'vocabulary', ?, '1.0', ?, '{}'::jsonb)
            """, releaseId, "1.0-" + releaseId.toString().substring(0, 8), checksum);
        jdbc.update("""
            INSERT INTO dataset_import_run(id, release_id, run_type, status, completed_at)
            VALUES (?, ?, 'APPLY', 'SUCCEEDED', now())
            """, UUID.randomUUID(), releaseId);
        jdbc.update("""
            INSERT INTO dataset_active_release(domain, release_id, activated_by)
            VALUES ('vocabulary', ?, 'test')
            ON CONFLICT (domain) DO UPDATE SET release_id = excluded.release_id
            """, releaseId);

        UUID publicPack = UUID.randomUUID();
        UUID plusPack = UUID.randomUUID();
        UUID hiddenPack = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO vocabulary_pack(id, release_id, source_topic, position, title_en, title_vi, hidden, access_mode, required_feature_key)
            VALUES (?, ?, 'topic-pub', 1, 'Daily Life', 'Đời sống', false, 'PUBLIC', null),
                   (?, ?, 'topic-plus', 2, 'Business Plus', 'Kinh doanh', false, 'FEATURE', 'vocabulary.plus-packs'),
                   (?, ?, 'topic-hidden', 3, 'Secret', 'Ẩn', true, 'PUBLIC', null)
            """, publicPack, releaseId, plusPack, releaseId, hiddenPack, releaseId);

        UUID unitVisible = UUID.randomUUID();
        UUID unitHidden = UUID.randomUUID();
        UUID unitPlus = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO vocabulary_unit(id, pack_id, position, level, title, hidden)
            VALUES (?, ?, 1, 'A2', 'Basics', false),
                   (?, ?, 2, 'B1', 'Hidden Unit', true),
                   (?, ?, 1, 'B2', 'Negotiation', false)
            """, unitVisible, publicPack, unitHidden, publicPack, unitPlus, plusPack);

        UUID audioAchieve = insertAsset("achieve.mp3", "audio/mpeg", "AUDIO_ACHIEVE".getBytes(StandardCharsets.UTF_8));
        UUID audioRun = insertAsset("run.mp3", "audio/mpeg", "AUDIO_RUN".getBytes(StandardCharsets.UTF_8));
        UUID audioHidden = insertAsset("hidden.mp3", "audio/mpeg", "AUDIO_HIDDEN".getBytes(StandardCharsets.UTF_8));
        UUID audioPlus = insertAsset("plus.mp3", "audio/mpeg", "AUDIO_PLUS".getBytes(StandardCharsets.UTF_8));

        UUID entryAchieve = insertEntry(unitVisible, 1, "achieve", "đạt được", "succeed in doing",
            "She achieved her goals.", audioAchieve, false);
        UUID entryRun = insertEntry(unitVisible, 2, "run", "chạy", "move fast",
            "He is running fast.", audioRun, false);
        UUID entryPepper = insertEntry(unitVisible, 3, "pepper", "hạt tiêu", "spice",
            "Pass the pepper please.", null, false);
        UUID entryApple = insertEntry(unitVisible, 4, "apple", "quả táo", "fruit",
            "Eat an apple daily.", null, false);
        UUID entryHidden = insertEntry(unitVisible, 5, "ghost", "ma", "spirit",
            "A ghost appeared.", audioHidden, true);
        UUID entryPlus = insertEntry(unitPlus, 1, "leverage", "đòn bẩy", "use to advantage",
            "Leverage your strengths.", audioPlus, false);

        UUID cardId = UUID.randomUUID();
        Instant originalDue = Instant.parse("2026-12-01T00:00:00Z");
        jdbc.update("""
            INSERT INTO vocabulary_card(id, learner_id, dedup_key, term, next_review_at, stability, difficulty, review_count)
            VALUES (?, ?, 'TEST:achieve', 'achieve', ?, 1.0, 5.0, 0)
            """, cardId, learnerA, java.sql.Timestamp.from(originalDue));

        // 1. Packs listing checks hidden filter + lock status
        var packs = studyService.listPacks(learnerA);
        assertThat(packs).extracting(VocabularyStudyService.PackSummary::id)
            .containsExactly(publicPack, plusPack);
        var plusSummary = packs.get(1);
        assertThat(plusSummary.locked()).isTrue();
        assertThat(plusSummary.lockReason()).isEqualTo("FEATURE_GRANT_REQUIRED");

        // 2. Hidden pack/unit and ungranted FEATURE pack/media rejections
        assertThatThrownBy(() -> studyService.listUnits(learnerA, hiddenPack))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> studyService.getUnitDetail(learnerA, unitHidden))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> studyService.listUnits(learnerA, plusPack))
            .isInstanceOf(FeatureEntitlementRequiredException.class);
        assertThatThrownBy(() -> studyService.authorizedMedia(learnerA, entryHidden, audioHidden))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> studyService.authorizedMedia(learnerA, entryPlus, audioPlus))
            .isInstanceOf(FeatureEntitlementRequiredException.class);
        assertThatThrownBy(() -> studyService.authorizedMedia(learnerA, entryAchieve, audioRun))
            .isInstanceOf(ResourceNotFoundException.class);

        // Plus unlocks plusPack and its media
        jdbc.update("""
            INSERT INTO learner_plus_period(learner_id, starts_at, ends_at, started_by)
            VALUES (?, now() - interval '1 minute', now() + interval '1 hour', 'admin')
            """, learnerA);
        assertThat(studyService.listPacks(learnerA).get(1).locked()).isFalse();
        assertThat(studyService.authorizedMedia(learnerA, entryPlus, audioPlus).id()).isEqualTo(audioPlus);

        // 3. Unit detail excludes hidden entry and computes modeAvailability
        var detail = studyService.getUnitDetail(learnerA, unitVisible);
        assertThat(detail.entries()).extracting(VocabularyStudyService.UnitEntryDetail::id)
            .containsExactly(entryAchieve, entryRun, entryPepper, entryApple);
        assertThat(detail.modeAvailability().get(PracticeMode.LISTEN_WRITE)).isEqualTo(2);
        assertThat(detail.modeAvailability().get(PracticeMode.CONTEXT_CLOZE)).isEqualTo(4);
        assertThat(detail.modeAvailability().get(PracticeMode.CHOOSE_MEANING)).isEqualTo(4);

        // 4. Start all 6 modes and verify full lifecycle on CONTEXT_CLOZE
        for (PracticeMode mode : PracticeMode.values()) {
            SessionView started = studyService.startSession(learnerA, unitVisible, mode, 10, "start-" + mode);
            assertThat(started.questions()).isNotEmpty();
        }

        SessionView clozeSession = studyService.startSession(
            learnerA, unitVisible, PracticeMode.CONTEXT_CLOZE, 10, "cloze-op-1"
        );
        SessionView replayedStart = studyService.startSession(
            learnerA, unitVisible, PracticeMode.CONTEXT_CLOZE, 10, "cloze-op-1"
        );
        assertThat(replayedStart.id()).isEqualTo(clozeSession.id());
        assertThatThrownBy(() -> studyService.startSession(
            learnerA, unitVisible, PracticeMode.LISTEN_WRITE, 10, "cloze-op-1"
        )).isInstanceOf(IdempotencyConflictException.class);

        // Cross-learner access returns 404
        assertThatThrownBy(() -> studyService.getSession(learnerB, clozeSession.id()))
            .isInstanceOf(ResourceNotFoundException.class);

        // Save answers with optimistic revision
        Map<UUID, String> answers = new LinkedHashMap<>();
        for (QuestionView q : clozeSession.questions()) {
            if (q.entryId().equals(entryAchieve)) {
                answers.put(q.id(), "  ACHIEVED ");
            } else if (q.entryId().equals(entryRun)) {
                answers.put(q.id(), "running");
            } else if (q.entryId().equals(entryPepper)) {
                answers.put(q.id(), "wrong");
            }
        }
        SessionView saved = studyService.saveAnswers(learnerA, clozeSession.id(), 1L, answers);
        assertThat(saved.revision()).isEqualTo(2L);

        // Stale revision throws StaleRevisionException
        assertThatThrownBy(() -> studyService.saveAnswers(learnerA, clozeSession.id(), 1L, answers))
            .isInstanceOf(StaleRevisionException.class);

        // Submit with Idempotency-Key emits event ONCE and updates entry progress
        SessionView submitted = studyService.submitSession(learnerA, clozeSession.id(), 2L, "submit-1");
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.result().correctCount()).isEqualTo(2);
        assertThat(submitted.result().totalCount()).isEqualTo(4);
        assertThat(unitEvents).hasSize(1);
        assertThat(unitEvents.getFirst().correctCount()).isEqualTo(2);

        // Retry submit with same key and initial revision 2 returns cached result with NO duplicate event
        SessionView retriedSubmit = studyService.submitSession(learnerA, clozeSession.id(), 2L, "submit-1");
        assertThat(retriedSubmit.revision()).isEqualTo(submitted.revision());
        assertThat(unitEvents).hasSize(1);

        // Entry progress updated for unitVisible
        var afterDetail = studyService.getUnitDetail(learnerA, unitVisible);
        assertThat(afterDetail.completedEntries()).isEqualTo(2);

        // Expired session cannot be saved
        SessionView expSession = studyService.startSession(learnerA, unitVisible, PracticeMode.MISSING_LETTERS, 2, null);
        jdbc.update("UPDATE vocabulary_study_session SET expires_at = now() - interval '1 minute' WHERE id = ?", expSession.id());
        assertThatThrownBy(() -> studyService.saveAnswers(learnerA, expSession.id(), 1L, Map.of()))
            .isInstanceOf(SessionExpiredException.class);

        // SRS card & review table completely untouched!
        Integer reviewCount = jdbc.queryForObject(
            "SELECT review_count FROM vocabulary_card WHERE id = ?", Integer.class, cardId
        );
        assertThat(reviewCount).isZero();
        assertThat(reviewEvents).isEmpty();

        // Also check CHOOSE_MEANING submission works cleanly
        SessionView mcq = studyService.startSession(learnerA, unitVisible, PracticeMode.CHOOSE_MEANING, 2, null);
        Map<UUID, String> mcqAnswers = new LinkedHashMap<>();
        for (QuestionView q : mcq.questions()) {
            List<QuestionOption> opts = q.options();
            assertThat(opts).hasSize(4);
            mcqAnswers.put(q.id(), opts.getFirst().key());
        }
        SessionView mcqSaved = studyService.saveAnswers(learnerA, mcq.id(), 1L, mcqAnswers);
        SessionView mcqSubmitted = studyService.submitSession(learnerA, mcq.id(), mcqSaved.revision(), "submit-mcq");
        assertThat(mcqSubmitted.status()).isEqualTo("SUBMITTED");

        // 5. Flashcard mode (DUE_AND_NEW vs ALL, SRS review, idempotency, stale reviewCount, vocabulary_card untouched)
        FlashcardDeckResponse learnerBBlocked = null;
        assertThatThrownBy(() -> studyService.getFlashcardDeck(learnerB, unitPlus, null, FlashcardScope.DUE_AND_NEW, 30))
            .isInstanceOf(FeatureEntitlementRequiredException.class);
        assertThat(learnerBBlocked).isNull();

        FlashcardDeckResponse unitDeckBefore = studyService.getFlashcardDeck(
            learnerA, unitVisible, null, FlashcardScope.DUE_AND_NEW, 30
        );
        assertThat(unitDeckBefore.items()).hasSize(4);
        assertThat(unitDeckBefore.counts().dueAndNewUnit()).isEqualTo(4);
        assertThat(unitDeckBefore.counts().allUnit()).isEqualTo(4);
        assertThat(unitDeckBefore.counts().allPack()).isEqualTo(4);
        assertThat(unitDeckBefore.items().getFirst().srsState()).isEqualTo("NEW");
        assertThat(unitDeckBefore.items().getFirst().intervalPreviews().keySet())
            .containsExactlyInAnyOrder(SpacedRepetitionScheduler.Rating.values());

        FlashcardReviewResponse flashReviewed = studyService.reviewFlashcard(
            learnerA, entryAchieve, SpacedRepetitionScheduler.Rating.GOOD, 0, "flash-rev-1"
        );
        assertThat(flashReviewed.reviewCount()).isEqualTo(1);
        assertThat(flashReviewed.srsState()).isEqualTo("LEARNING");
        assertThat(reviewEvents).hasSize(1);
        assertThat(reviewEvents.getFirst().cardId()).isEqualTo(entryAchieve);

        // Idempotent retry produces no duplicate event
        FlashcardReviewResponse flashRetry = studyService.reviewFlashcard(
            learnerA, entryAchieve, SpacedRepetitionScheduler.Rating.GOOD, 0, "flash-rev-1"
        );
        assertThat(flashRetry.reviewCount()).isEqualTo(1);
        assertThat(reviewEvents).hasSize(1);

        // Stale expectedReviewCount rejected with 409
        assertThatThrownBy(() -> studyService.reviewFlashcard(
            learnerA, entryAchieve, SpacedRepetitionScheduler.Rating.EASY, 0, "flash-rev-stale"
        )).isInstanceOf(StaleRevisionException.class);

        // entryAchieve drops out of DUE_AND_NEW deck, but remains in ALL deck
        FlashcardDeckResponse unitDeckAfter = studyService.getFlashcardDeck(
            learnerA, unitVisible, null, FlashcardScope.DUE_AND_NEW, 30
        );
        assertThat(unitDeckAfter.items()).extracting(FlashcardItem::entryId).doesNotContain(entryAchieve);
        assertThat(unitDeckAfter.counts().dueAndNewUnit()).isEqualTo(3);

        FlashcardDeckResponse unitDeckAll = studyService.getFlashcardDeck(
            learnerA, unitVisible, null, FlashcardScope.ALL, 30
        );
        assertThat(unitDeckAll.items()).hasSize(4);
        assertThat(unitDeckAll.items().getFirst().srsState()).isEqualTo("LEARNING");

        // Immediate repeat review on a not-yet-due card updates SRS (free practice) WITHOUT emitting mission event
        FlashcardReviewResponse flashRepeat = studyService.reviewFlashcard(
            learnerA, entryAchieve, SpacedRepetitionScheduler.Rating.GOOD, 1, "flash-rev-repeat-non-due"
        );
        assertThat(flashRepeat.reviewCount()).isEqualTo(2);
        assertThat(reviewEvents).hasSize(1);

        // Personal vocabulary_card table remains untouched!
        assertThat(jdbc.queryForObject("SELECT review_count FROM vocabulary_card WHERE id = ?", Integer.class, cardId))
            .isZero();

        // 6. GUESS_WORD: hints, per-question check, lock against overwrite, giveUp, SRS untouched
        int reviewEventsBeforeGuess = reviewEvents.size();
        SessionView guessSession = studyService.startSession(learnerA, unitVisible, PracticeMode.GUESS_WORD, 4, "guess-start");
        assertThat(jdbc.queryForObject("""
            SELECT request_fingerprint = encode(sha256(convert_to(?, 'UTF8')), 'hex')
            FROM operation_idempotency_record
            WHERE learner_id = ? AND operation = 'vocabulary.study.start' AND idempotency_key = 'guess-start'
            """, Boolean.class, "UNIT|" + unitVisible + "|GUESS_WORD|4", learnerA)).isTrue();
        assertThat(studyService.startSession(
            learnerA, unitVisible, PracticeMode.GUESS_WORD, 4, "guess-start"
        ).id()).isEqualTo(guessSession.id());
        QuestionView pepperQ = guessSession.questions().stream()
            .filter(q -> q.entryId().equals(entryPepper))
            .findFirst()
            .orElseThrow();
        assertThat(pepperQ.maskedTerm()).isEqualTo("******");
        assertThat(pepperQ.maxHints()).isEqualTo(5);

        HintResponse h1 = studyService.requestHint(learnerA, guessSession.id(), pepperQ.id());
        assertThat(h1.maskedTerm()).isEqualTo("p*****");
        assertThat(h1.hintsUsed()).isEqualTo(1);
        assertThat(h1.hintsRemaining()).isEqualTo(4);

        CheckQuestionResponse pepperChecked = studyService.checkQuestion(
            learnerA, guessSession.id(), pepperQ.id(), guessSession.revision(), "pepper", false
        );
        assertThat(pepperChecked.correct()).isTrue();
        assertThat(pepperChecked.hintsUsed()).isEqualTo(1);

        // Checked question cannot accept more hints or be overwritten via saveAnswers
        assertThatThrownBy(() -> studyService.requestHint(learnerA, guessSession.id(), pepperQ.id()))
            .isInstanceOf(StateConflictException.class);
        assertThatThrownBy(() -> studyService.saveAnswers(
            learnerA, guessSession.id(), pepperChecked.revision(), Map.of(pepperQ.id(), "changed")
        )).isInstanceOf(StateConflictException.class);

        // Give up on another question
        QuestionView appleQ = guessSession.questions().stream()
            .filter(q -> q.entryId().equals(entryApple))
            .findFirst()
            .orElseThrow();
        CheckQuestionResponse appleGaveUp = studyService.checkQuestion(
            learnerA, guessSession.id(), appleQ.id(), pepperChecked.revision(), null, true
        );
        assertThat(appleGaveUp.correct()).isFalse();
        assertThat(appleGaveUp.gaveUp()).isTrue();
        assertThat(appleGaveUp.correctAnswer()).isEqualTo("apple");

        // Hint exhausted check on remaining question ("run" has maxHints=2)
        QuestionView runQ = guessSession.questions().stream()
            .filter(q -> q.entryId().equals(entryRun))
            .findFirst()
            .orElseThrow();
        studyService.requestHint(learnerA, guessSession.id(), runQ.id());
        studyService.requestHint(learnerA, guessSession.id(), runQ.id());
        assertThatThrownBy(() -> studyService.requestHint(learnerA, guessSession.id(), runQ.id()))
            .isInstanceOf(RequestValidationException.class);

        SessionView guessSubmitted = studyService.submitSession(
            learnerA, guessSession.id(), appleGaveUp.revision(), "guess-submit"
        );
        assertThat(guessSubmitted.status()).isEqualTo("SUBMITTED");
        QuestionFeedback appleFeedback = guessSubmitted.result().questions().stream()
            .filter(f -> f.questionId().equals(appleQ.id()))
            .findFirst()
            .orElseThrow();
        assertThat(appleFeedback.gaveUp()).isTrue();
        assertThat(appleFeedback.correct()).isFalse();
        assertThat(reviewEvents).hasSize(reviewEventsBeforeGuess);

        // 7. Pack-wide multi-mode session ("Cả bộ" + chọn nhiều dạng bài)
        int unitEventsBeforePack = unitEvents.size();
        SessionView packSession = studyService.startPackSession(
            learnerA,
            publicPack,
            PracticeMode.MIXED,
            List.of(PracticeMode.CHOOSE_MEANING, PracticeMode.CHOOSE_WORD, PracticeMode.GUESS_WORD),
            10,
            "pack-mixed-1"
        );
        assertThat(packSession.unitId()).isNull();
        assertThat(packSession.mode()).isEqualTo(PracticeMode.MIXED);
        assertThat(packSession.questions()).hasSize(4);

        SessionView packSubmitted = studyService.submitSession(learnerA, packSession.id(), packSession.revision(), "pack-submit-1");
        assertThat(packSubmitted.status()).isEqualTo("SUBMITTED");
        assertThat(unitEvents).hasSize(unitEventsBeforePack + 1);
        assertThat(unitEvents.getLast().unitId()).isNull();

        // 8. Slice-scoped unit session selects ONLY requested entryIds while still building 4-option distractors from unit
        SessionView sliceSession = studyService.startSession(
            learnerA,
            unitVisible,
            PracticeMode.CHOOSE_MEANING,
            null,
            null,
            List.of(entryApple, entryPepper),
            "slice-unit-1"
        );
        assertThat(sliceSession.questions()).hasSize(2);
        assertThat(sliceSession.questions())
            .extracting(QuestionView::entryId)
            .containsExactlyInAnyOrder(entryApple, entryPepper);
        assertThat(sliceSession.questions().getFirst().options()).hasSize(4);

        // Out-of-scope entryId is rejected with RequestValidationException
        assertThatThrownBy(() -> studyService.startSession(
            learnerA,
            unitVisible,
            PracticeMode.CHOOSE_MEANING,
            null,
            null,
            List.of(entryApple, UUID.randomUUID()),
            "slice-unit-invalid"
        )).isInstanceOf(RequestValidationException.class);
    }

    private UUID insertAsset(String filename, String mimeType, byte[] bytes) throws Exception {
        UUID id = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String key = "test/" + id + "/" + filename;
        storage.put(key, mimeType, bytes);
        jdbc.update("""
            INSERT INTO content_asset(id, sha256, storage_key, mime_type, size_bytes, filename, synthetic, created_by)
            VALUES (?, ?, ?, ?, ?, ?, false, 'test')
            """, id, sha, key, mimeType, bytes.length, filename);
        return id;
    }

    private UUID insertEntry(
        UUID unitId,
        int pos,
        String term,
        String meaningVi,
        String defEn,
        String example,
        UUID usAudioId,
        boolean hidden
    ) {
        UUID id = UUID.randomUUID();
        String contentJson = mapper.writeValueAsString(Map.of(
            "ipaUk", "/" + term + "/",
            "ipaUs", "/" + term + "/",
            "senses", List.of(Map.of("pos", "v.", "definitionEn", defEn, "meaningVi", meaningVi)),
            "examples", List.of(example, "Second example."),
            "collocations", List.of(),
            "family", List.of(),
            "synonyms", List.of(),
            "antonyms", List.of(),
            "examplesVi", List.of("Ví dụ 1", "Ví dụ 2")
        ));
        jdbc.update("""
            INSERT INTO vocabulary_unit_entry(
                id, unit_id, position, source_id, term, content, us_audio_id, hidden, revision
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 1)
            """, id, unitId, pos, "src-" + id, term, contentJson, usAudioId, hidden);
        return id;
    }

    private static final class InMemoryStorage implements ObjectStoragePort {
        private final Map<String, byte[]> files = new HashMap<>();

        @Override public StoredObject put(String key, String contentType, byte[] bytes) {
            files.put(key, bytes);
            return new StoredObject(key, bytes.length, "etag");
        }
        @Override public InputStream get(String key) { return new ByteArrayInputStream(files.get(key)); }
        @Override public boolean exists(String key) { return files.containsKey(key); }
        @Override public void delete(String key) { files.remove(key); }
        @Override public java.net.URI createDownloadUrl(String key, java.time.Duration ttl) {
            return java.net.URI.create("https://example.test/" + key);
        }
    }
}
