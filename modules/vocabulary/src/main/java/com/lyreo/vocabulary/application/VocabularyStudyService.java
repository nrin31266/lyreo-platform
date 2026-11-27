package com.lyreo.vocabulary.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.SessionExpiredException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureAccess;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import com.lyreo.vocabulary.application.port.VocabularyStudyRepository;
import com.lyreo.vocabulary.application.port.VocabularyStudyRepository.*;
import com.lyreo.vocabulary.domain.PracticeMode;
import com.lyreo.vocabulary.domain.VocabularyAnswerGrader;
import com.lyreo.vocabulary.domain.VocabularyExerciseGenerator;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Application service for learner vocabulary study catalog browsing, authorized media access,
 * and 6-mode practice sessions.
 */
public class VocabularyStudyService {
    private static final long SESSION_TTL_SECONDS = 24L * 60L * 60L;

    private final VocabularyStudyRepository repository;
    private final EntitlementService entitlements;
    private final AssetStore assets;
    private final OperationIdempotencyService idempotency;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final VocabularyExerciseGenerator generator;
    private final VocabularyAnswerGrader grader;

    public VocabularyStudyService(
        VocabularyStudyRepository repository,
        EntitlementService entitlements,
        AssetStore assets,
        OperationIdempotencyService idempotency,
        ApplicationEventPublisher events,
        ObjectMapper json
    ) {
        this.repository = repository;
        this.entitlements = entitlements;
        this.assets = assets;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.generator = new VocabularyExerciseGenerator();
        this.grader = new VocabularyAnswerGrader();
    }

    public List<PackSummary> listPacks(UUID learnerId) {
        Optional<UUID> releaseId = repository.findActiveReleaseId();
        if (releaseId.isEmpty()) {
            return List.of();
        }
        List<PackRow> rows = repository.findVisiblePacks(releaseId.get(), learnerId);
        Map<String, FeatureAccess> accessCache = new HashMap<>();
        List<PackSummary> result = new ArrayList<>(rows.size());
        for (PackRow row : rows) {
            boolean locked = false;
            String lockReason = null;
            if ("FEATURE".equals(row.accessMode()) && row.requiredFeatureKey() != null) {
                FeatureAccess access = accessCache.computeIfAbsent(
                    row.requiredFeatureKey(),
                    key -> entitlements.evaluateFeature(learnerId, FeatureKey.of(key))
                );
                locked = !access.granted();
                lockReason = locked ? access.lockReason() : null;
            }
            result.add(new PackSummary(
                row.id(),
                row.titleEn(),
                row.titleVi(),
                row.unitCount(),
                row.entryCount(),
                row.completedEntries(),
                row.accessMode(),
                row.requiredFeatureKey(),
                locked,
                lockReason,
                row.coverImageId()
            ));
        }
        return result;
    }

    public List<UnitSummary> listUnits(UUID learnerId, UUID packId) {
        PackRow pack = repository.findVisiblePack(packId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary pack not found"));
        requirePackEntitlement(learnerId, pack.accessMode(), pack.requiredFeatureKey());
        return repository.findVisibleUnits(packId, learnerId).stream()
            .map(row -> new UnitSummary(
                row.id(),
                row.position(),
                row.level(),
                row.title(),
                row.entryCount(),
                row.completedEntries(),
                row.revision()
            ))
            .toList();
    }

    public UnitDetail getUnitDetail(UUID learnerId, UUID unitId) {
        UnitWithPackRow unit = repository.findVisibleUnit(unitId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary unit not found"));
        requirePackEntitlement(learnerId, unit.packAccessMode(), unit.packRequiredFeatureKey());

        List<EntryWithProgressRow> entryRows = repository.findVisibleEntries(unitId, learnerId);
        List<StudyEntrySource> sources = entryRows.stream().map(VocabularyStudyService::toSource).toList();
        Map<PracticeMode, Integer> modeAvailability = generator.modeAvailability(sources);

        long completedEntries = entryRows.stream()
            .filter(row -> "COMPLETED".equals(row.progressStatus()))
            .count();

        List<UnitEntryDetail> entries = entryRows.stream()
            .map(row -> new UnitEntryDetail(
                row.id(),
                row.position(),
                row.term(),
                row.revision(),
                row.content(),
                row.imageId(),
                row.ukAudioId(),
                row.usAudioId(),
                row.exampleAudioId(),
                row.secondExampleAudioId(),
                row.progressStatus()
            ))
            .toList();

        return new UnitDetail(
            unit.id(),
            unit.packId(),
            unit.position(),
            unit.level(),
            unit.title(),
            unit.revision(),
            entries.size(),
            completedEntries,
            modeAvailability,
            entries
        );
    }

    public AssetStore.Asset authorizedPackCover(UUID learnerId, UUID packId) {
        PackRow pack = repository.findVisiblePack(packId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary pack not found"));
        requirePackEntitlement(learnerId, pack.accessMode(), pack.requiredFeatureKey());
        if (pack.coverImageId() == null) {
            throw new ResourceNotFoundException("Vocabulary pack cover not found");
        }
        return assets.get(pack.coverImageId());
    }

    public AssetStore.Asset authorizedMedia(UUID learnerId, UUID entryId, UUID assetId) {
        EntryMediaAccessRow entry = repository.findVisibleEntryForMedia(entryId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary entry not found"));
        requirePackEntitlement(learnerId, entry.packAccessMode(), entry.packRequiredFeatureKey());
        if (!entry.ownsAsset(assetId)) {
            throw new ResourceNotFoundException("Vocabulary media not found");
        }
        return assets.get(assetId);
    }

    @Transactional
    public SessionView startSession(
        UUID learnerId,
        UUID unitId,
        PracticeMode mode,
        Integer limit,
        String idempotencyKey
    ) {
        if (mode == null) {
            throw new RequestValidationException("Practice mode is required");
        }
        if (limit != null && (limit < 1 || limit > 50)) {
            throw new RequestValidationException("limit must be between 1 and 50");
        }

        String fingerprint = unitId + "|" + mode.name() + "|" + limit;
        return executeIdempotent(
            learnerId,
            "vocabulary.study.start",
            idempotencyKey,
            fingerprint,
            SessionView.class,
            () -> {
                UnitWithPackRow unit = repository.findVisibleUnit(unitId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vocabulary unit not found"));
                requirePackEntitlement(learnerId, unit.packAccessMode(), unit.packRequiredFeatureKey());

                List<EntryWithProgressRow> entryRows = repository.findVisibleEntries(unitId, learnerId);
                List<StudyEntrySource> sources = entryRows.stream().map(VocabularyStudyService::toSource).toList();

                UUID sessionId = UUID.randomUUID();
                long seed = sessionId.getMostSignificantBits() ^ sessionId.getLeastSignificantBits();
                int requestedLimit = limit != null ? limit : Math.max(1, sources.size());
                List<SnapshotQuestion> questions = generator.generateQuestions(sources, mode, requestedLimit, seed);
                if (questions.isEmpty()) {
                    throw new RequestValidationException("No eligible questions available for mode " + mode);
                }

                SessionSnapshot snapshot = new SessionSnapshot(
                    unit.packId(),
                    unit.id(),
                    unit.title(),
                    unit.level(),
                    unit.revision(),
                    mode,
                    questions
                );
                Instant now = Instant.now();
                Instant expiresAt = now.plusSeconds(SESSION_TTL_SECONDS);
                repository.insertSession(
                    sessionId,
                    learnerId,
                    unit.packId(),
                    unit.id(),
                    mode,
                    json.writeValueAsString(snapshot),
                    now,
                    expiresAt
                );
                return repository.findSession(sessionId, learnerId, false)
                    .map(this::toView)
                    .orElseThrow();
            }
        );
    }

    public List<SessionSummary> listSessions(UUID learnerId, UUID unitId, String status) {
        return listSessionsPage(learnerId, unitId, status, 0, 100).items();
    }

    public SessionPage listSessionsPage(UUID learnerId, UUID unitId, String status, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new RequestValidationException("page must be between 0 and 100000 and size between 1 and 100");
        }
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !Set.of("OPEN", "SUBMITTED", "EXPIRED").contains(normalizedStatus)) {
            throw new RequestValidationException("status must be OPEN, SUBMITTED, or EXPIRED");
        }
        long total = repository.countSessions(learnerId, unitId, normalizedStatus);
        List<SessionSummary> items = total == 0
            ? List.of()
            : repository.findSessionsPage(learnerId, unitId, normalizedStatus, page * size, size).stream()
                .map(row -> {
                    SessionSnapshot snapshot = readSnapshot(row);
                    SessionResult result = readResult(row);
                    return new SessionSummary(
                        row.id(),
                        row.packId(),
                        row.unitId(),
                        snapshot.unitTitle(),
                        row.mode(),
                        statusOf(row),
                        row.revision(),
                        snapshot.questions().size(),
                        result != null ? result.correctCount() : null,
                        result != null ? result.totalCount() : null,
                        row.createdAt(),
                        row.expiresAt(),
                        row.submittedAt()
                    );
                })
                .toList();
        return new SessionPage(items, total, page, size, (long) (page + 1) * size < total);
    }

    public SessionView getSession(UUID learnerId, UUID sessionId) {
        SessionRow row = repository.findSession(sessionId, learnerId, false)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary study session not found"));
        return toView(row);
    }

    @Transactional
    public SessionView saveAnswers(UUID learnerId, UUID sessionId, long revision, Map<UUID, String> answers) {
        SessionRow row = repository.findSession(sessionId, learnerId, true)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary study session not found"));
        requireOpenAndRevision(row, revision);

        SessionSnapshot snapshot = readSnapshot(row);
        validateAnswers(snapshot, answers);

        int updated = repository.updateAnswers(sessionId, revision, json.writeValueAsString(answers));
        if (updated != 1) {
            throw new StaleRevisionException("Session revision mismatch", row.revision());
        }
        return repository.findSession(sessionId, learnerId, false)
            .map(this::toView)
            .orElseThrow();
    }

    @Transactional
    public SessionView submitSession(UUID learnerId, UUID sessionId, long revision, String idempotencyKey) {
        String fingerprint = sessionId + "|" + revision;
        return executeIdempotent(
            learnerId,
            "vocabulary.study.submit",
            idempotencyKey,
            fingerprint,
            SessionView.class,
            () -> {
                SessionRow row = repository.findSession(sessionId, learnerId, true)
                    .orElseThrow(() -> new ResourceNotFoundException("Vocabulary study session not found"));
                if (row.submittedAt() != null) {
                    return toView(row);
                }
                requireOpenAndRevision(row, revision);

                SessionSnapshot snapshot = readSnapshot(row);
                Map<UUID, String> answers = readAnswers(row);
                List<QuestionFeedback> feedback = new ArrayList<>(snapshot.questions().size());
                int correctCount = 0;
                Instant now = Instant.now();

                for (SnapshotQuestion q : snapshot.questions()) {
                    String learnerAnswer = answers.get(q.id());
                    boolean correct = grader.isCorrect(q, learnerAnswer);
                    if (correct) {
                        correctCount++;
                    }
                    feedback.add(new QuestionFeedback(
                        q.id(),
                        q.entryId(),
                        q.prompt(),
                        q.baseTerm(),
                        learnerAnswer,
                        q.correctAnswer(),
                        correct
                    ));
                    repository.upsertEntryProgress(learnerId, q.entryId(), row.unitId(), row.packId(), correct, now);
                }

                int totalCount = snapshot.questions().size();
                int accuracy = totalCount == 0 ? 0 : (int) Math.round((correctCount * 100.0) / totalCount);
                SessionResult result = new SessionResult(correctCount, totalCount, accuracy, List.copyOf(feedback));

                int updated = repository.markSubmitted(sessionId, revision, json.writeValueAsString(result), now);
                if (updated != 1) {
                    throw new StaleRevisionException("Session revision mismatch", row.revision());
                }

                events.publishEvent(new VocabularyUnitCompletedEvent(
                    sessionId,
                    learnerId,
                    row.packId(),
                    row.unitId(),
                    correctCount,
                    totalCount,
                    now
                ));

                return repository.findSession(sessionId, learnerId, false)
                    .map(this::toView)
                    .orElseThrow();
            }
        );
    }

    private void requirePackEntitlement(UUID learnerId, String accessMode, String featureKey) {
        if ("FEATURE".equals(accessMode) && featureKey != null) {
            entitlements.requireFeature(learnerId, FeatureKey.of(featureKey));
        }
    }

    private void requireOpenAndRevision(SessionRow row, long expectedRevision) {
        if (row.submittedAt() != null) {
            throw new StateConflictException("Session is already submitted");
        }
        if (!row.expiresAt().isAfter(Instant.now())) {
            throw new SessionExpiredException("Session expired", row.expiresAt());
        }
        if (row.revision() != expectedRevision) {
            throw new StaleRevisionException("Session changed; reload before continuing", row.revision());
        }
    }

    private void validateAnswers(SessionSnapshot snapshot, Map<UUID, String> answers) {
        if (answers == null || answers.size() > snapshot.questions().size()) {
            throw new RequestValidationException("Invalid answers payload");
        }
        Map<UUID, SnapshotQuestion> byId = new HashMap<>();
        snapshot.questions().forEach(q -> byId.put(q.id(), q));
        for (Map.Entry<UUID, String> entry : answers.entrySet()) {
            SnapshotQuestion q = byId.get(entry.getKey());
            if (q == null || entry.getValue() == null) {
                throw new RequestValidationException("Answers must reference questions in this session");
            }
            if ((q.mode() == PracticeMode.CHOOSE_MEANING || q.mode() == PracticeMode.CHOOSE_WORD)
                && q.options() != null
                && q.options().stream().noneMatch(o -> o.key().equals(entry.getValue()))) {
                throw new RequestValidationException("Answer must match a valid option key for question " + q.id());
            }
        }
    }

    private <T> T executeIdempotent(
        UUID learnerId,
        String operation,
        String idempotencyKey,
        String fingerprint,
        Class<T> responseType,
        java.util.function.Supplier<T> action
    ) {
        if (idempotency == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        return idempotency.execute(learnerId, operation, idempotencyKey, fingerprint, responseType, action);
    }

    private SessionView toView(SessionRow row) {
        SessionSnapshot snapshot = readSnapshot(row);
        List<QuestionView> questionViews = snapshot.questions().stream()
            .map(QuestionView::from)
            .toList();
        return new SessionView(
            row.id(),
            row.packId(),
            row.unitId(),
            snapshot.unitTitle(),
            row.mode(),
            statusOf(row),
            row.revision(),
            row.createdAt(),
            row.expiresAt(),
            row.submittedAt(),
            questionViews,
            readAnswers(row),
            readResult(row)
        );
    }

    private SessionSnapshot readSnapshot(SessionRow row) {
        return json.readValue(row.snapshotJson(), SessionSnapshot.class);
    }

    private Map<UUID, String> readAnswers(SessionRow row) {
        if (row.answersJson() == null || row.answersJson().isBlank()) {
            return Map.of();
        }
        return json.readValue(row.answersJson(), new TypeReference<>() {});
    }

    private SessionResult readResult(SessionRow row) {
        if (row.resultJson() == null || row.resultJson().isBlank()) {
            return null;
        }
        return json.readValue(row.resultJson(), SessionResult.class);
    }

    private static String statusOf(SessionRow row) {
        if (row.submittedAt() != null) {
            return "SUBMITTED";
        }
        return row.expiresAt().isAfter(Instant.now()) ? "OPEN" : "EXPIRED";
    }

    private static StudyEntrySource toSource(EntryWithProgressRow row) {
        VocabularyCatalog.LearningContent c = row.content();
        List<StudySense> senses = c.senses() == null ? List.of() : c.senses().stream()
            .map(s -> new StudySense(s.pos(), s.definitionEn(), s.meaningVi()))
            .toList();
        List<StudyContextNote> notes = c.contextNotes() == null ? List.of() : c.contextNotes().stream()
            .map(n -> new StudyContextNote(n.pos(), n.meaningVi()))
            .toList();
        return new StudyEntrySource(
            row.id(),
            row.unitId(),
            row.position(),
            row.term(),
            c.ipaUk(),
            c.ipaUs(),
            senses,
            c.examples() != null ? c.examples() : List.of(),
            c.examplesVi() != null ? c.examplesVi() : List.of(),
            notes,
            row.imageId(),
            row.ukAudioId(),
            row.usAudioId(),
            row.exampleAudioId(),
            row.secondExampleAudioId(),
            row.revision()
        );
    }

    public record PackSummary(
        UUID id,
        String titleEn,
        String titleVi,
        long unitCount,
        long entryCount,
        long completedEntries,
        String accessMode,
        String requiredFeatureKey,
        boolean locked,
        String lockReason,
        UUID coverImageId
    ) {
        @com.fasterxml.jackson.annotation.JsonProperty("coverUrl")
        public String coverUrl() {
            return coverImageId != null ? "/api/v1/vocabulary/packs/" + id + "/cover" : null;
        }
    }

    public record SessionPage(
        List<SessionSummary> items,
        long total,
        int page,
        int size,
        boolean hasMore
    ) {}

    public record UnitSummary(
        UUID id,
        int position,
        String level,
        String title,
        long entryCount,
        long completedEntries,
        long revision
    ) {}

    public record UnitEntryDetail(
        UUID id,
        int position,
        String term,
        long revision,
        VocabularyCatalog.LearningContent content,
        UUID imageId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID exampleAudioId,
        UUID secondExampleAudioId,
        String progressStatus
    ) {}

    public record UnitDetail(
        UUID id,
        UUID packId,
        int position,
        String level,
        String title,
        long revision,
        long entryCount,
        long completedEntries,
        Map<PracticeMode, Integer> modeAvailability,
        List<UnitEntryDetail> entries
    ) {}
}
