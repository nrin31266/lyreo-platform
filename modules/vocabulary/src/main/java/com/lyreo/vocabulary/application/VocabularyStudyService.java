package com.lyreo.vocabulary.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.SessionExpiredException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureAccess;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import com.lyreo.vocabulary.application.port.VocabularyStudyRepository;
import com.lyreo.vocabulary.application.port.VocabularyStudyRepository.*;
import com.lyreo.vocabulary.domain.PracticeMode;
import com.lyreo.vocabulary.domain.VocabularyAnswerGrader;
import com.lyreo.vocabulary.domain.VocabularyExerciseGenerator;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Application service for learner vocabulary study catalog browsing, authorized media access,
 * flashcard SRS study, multi-mode/pack practice sessions, and GUESS_WORD hints/checks.
 */
public class VocabularyStudyService {
    private static final long SESSION_TTL_SECONDS = 24L * 60L * 60L;

    private final VocabularyStudyRepository repository;
    private final EntitlementService entitlements;
    private final AssetStore assets;
    private final OperationIdempotencyService idempotency;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final SpacedRepetitionScheduler scheduler;
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
        this(repository, entitlements, assets, idempotency, events, json, null);
    }

    public VocabularyStudyService(
        VocabularyStudyRepository repository,
        EntitlementService entitlements,
        AssetStore assets,
        OperationIdempotencyService idempotency,
        ApplicationEventPublisher events,
        ObjectMapper json,
        SpacedRepetitionScheduler scheduler
    ) {
        this.repository = repository;
        this.entitlements = entitlements;
        this.assets = assets;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.scheduler = scheduler;
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

    public FlashcardDeckResponse getFlashcardDeck(
        UUID learnerId,
        UUID unitId,
        UUID packId,
        FlashcardScope scope,
        int limit
    ) {
        if (unitId == null && packId == null) {
            throw new RequestValidationException("unitId or packId is required");
        }
        if (limit < 1 || limit > 200) {
            throw new RequestValidationException("limit must be between 1 and 200");
        }
        FlashcardScope effectiveScope = scope != null ? scope : FlashcardScope.DUE_AND_NEW;

        UUID resolvedPackId;
        if (unitId != null) {
            UnitWithPackRow unit = repository.findVisibleUnit(unitId)
                .orElseThrow(() -> new ResourceNotFoundException("Vocabulary unit not found"));
            requirePackEntitlement(learnerId, unit.packAccessMode(), unit.packRequiredFeatureKey());
            if (packId != null && !packId.equals(unit.packId())) {
                throw new RequestValidationException("unitId does not belong to the requested packId");
            }
            resolvedPackId = unit.packId();
        } else {
            PackRow pack = repository.findVisiblePack(packId)
                .orElseThrow(() -> new ResourceNotFoundException("Vocabulary pack not found"));
            requirePackEntitlement(learnerId, pack.accessMode(), pack.requiredFeatureKey());
            resolvedPackId = pack.id();
        }

        Instant now = Instant.now();
        List<FlashcardEntryRow> packEntries = repository.findFlashcardEntriesByPack(resolvedPackId, learnerId);

        int dueAndNewPack = 0;
        int allPack = packEntries.size();
        Integer dueAndNewUnit = unitId != null ? 0 : null;
        Integer allUnit = unitId != null ? 0 : null;
        int dueCount = 0;
        int newCount = 0;
        int learningCount = 0;
        int masteredCount = 0;

        List<FlashcardEntryRow> dueRows = new ArrayList<>();
        List<FlashcardEntryRow> newRows = new ArrayList<>();
        List<FlashcardEntryRow> allScopeRows = new ArrayList<>();

        for (FlashcardEntryRow row : packEntries) {
            String state = classifySrsState(row.srsReviewCount(), row.srsNextReviewAt(), row.srsStability(), now);
            boolean isDueOrNew = "DUE".equals(state) || "NEW".equals(state);
            if (isDueOrNew) {
                dueAndNewPack++;
            }
            boolean inScope = unitId == null || unitId.equals(row.unitId());
            if (!inScope) {
                continue;
            }
            if (unitId != null) {
                allUnit = allUnit + 1;
                if (isDueOrNew) {
                    dueAndNewUnit = dueAndNewUnit + 1;
                }
            }
            switch (state) {
                case "DUE" -> {
                    dueCount++;
                    dueRows.add(row);
                }
                case "NEW" -> {
                    newCount++;
                    newRows.add(row);
                }
                case "MASTERED" -> masteredCount++;
                default -> learningCount++;
            }
            allScopeRows.add(row);
        }

        List<FlashcardEntryRow> ordered;
        if (effectiveScope == FlashcardScope.DUE_AND_NEW) {
            dueRows.sort(Comparator.comparing(
                FlashcardEntryRow::srsNextReviewAt,
                Comparator.nullsFirst(Comparator.naturalOrder())
            ).thenComparingInt(FlashcardEntryRow::unitPosition).thenComparingInt(FlashcardEntryRow::position));
            ordered = new ArrayList<>(dueRows.size() + newRows.size());
            ordered.addAll(dueRows);
            ordered.addAll(newRows);
        } else {
            ordered = allScopeRows;
        }

        List<FlashcardItem> items = ordered.stream()
            .limit(limit)
            .map(row -> toFlashcardItem(row, now))
            .toList();

        return new FlashcardDeckResponse(
            resolvedPackId,
            unitId,
            effectiveScope,
            new FlashcardCounts(
                dueAndNewUnit,
                allUnit,
                dueAndNewPack,
                allPack,
                dueCount,
                newCount,
                learningCount,
                masteredCount
            ),
            items
        );
    }

    @Transactional
    public FlashcardReviewResponse reviewFlashcard(
        UUID learnerId,
        UUID entryId,
        SpacedRepetitionScheduler.Rating rating,
        Integer expectedReviewCount,
        String idempotencyKey
    ) {
        if (rating == null) {
            throw new RequestValidationException("rating is required");
        }
        if (expectedReviewCount != null && expectedReviewCount < 0) {
            throw new RequestValidationException("expectedReviewCount must be >= 0");
        }

        String fingerprint = entryId + "|" + rating.name() + "|" + expectedReviewCount;
        return executeIdempotent(
            learnerId,
            "vocabulary.flashcard.review",
            idempotencyKey,
            fingerprint,
            FlashcardReviewResponse.class,
            () -> {
                FlashcardEntryRow entry = repository.findVisibleEntryForFlashcard(entryId, learnerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vocabulary entry not found"));
                requirePackEntitlement(learnerId, entry.packAccessMode(), entry.packRequiredFeatureKey());

                Instant now = Instant.now();
                EntrySrsState locked = repository.lockOrCreateEntryProgressForSrs(
                    learnerId,
                    entry.entryId(),
                    entry.unitId(),
                    entry.packId(),
                    now
                );
                if (expectedReviewCount != null && locked.reviewCount() != expectedReviewCount) {
                    throw new StaleRevisionException(
                        "Vocabulary entry flashcard review count changed; reload before reviewing.",
                        (long) locked.reviewCount()
                    );
                }

                if (scheduler == null) {
                    throw new IllegalStateException("SpacedRepetitionScheduler is not configured");
                }
                var result = scheduler.schedule(
                    locked.stability(),
                    locked.difficulty(),
                    locked.reviewCount(),
                    locked.lapseCount(),
                    rating,
                    now
                );
                boolean eligibleForMissionCredit = locked.reviewCount() == 0
                    || locked.nextReviewAt() == null
                    || !locked.nextReviewAt().isAfter(now);

                UUID reviewId = repository.applyEntryFlashcardReview(
                    learnerId,
                    entry.entryId(),
                    entry.unitId(),
                    entry.packId(),
                    rating,
                    result,
                    now
                );

                if (eligibleForMissionCredit) {
                    events.publishEvent(new VocabularyReviewCompletedEvent(
                        reviewId,
                        learnerId,
                        entry.entryId(),
                        null,
                        rating.name(),
                        rating != SpacedRepetitionScheduler.Rating.AGAIN,
                        now
                    ));
                }

                int newReviewCount = locked.reviewCount() + 1;
                int newLapseCount = locked.lapseCount() + (rating == SpacedRepetitionScheduler.Rating.AGAIN ? 1 : 0);
                String srsState = classifySrsState(newReviewCount, result.nextReviewAt(), result.stability(), now);
                Map<SpacedRepetitionScheduler.Rating, IntervalPreview> previews = computeIntervalPreviews(
                    result.stability(),
                    result.difficulty(),
                    newReviewCount,
                    newLapseCount,
                    now
                );

                return new FlashcardReviewResponse(
                    entry.entryId(),
                    entry.unitId(),
                    entry.packId(),
                    srsState,
                    result.nextReviewAt(),
                    result.stability(),
                    result.difficulty(),
                    newReviewCount,
                    newLapseCount,
                    previews
                );
            }
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
        return startSession(learnerId, unitId, mode, null, limit, null, idempotencyKey);
    }

    @Transactional
    public SessionView startSession(
        UUID learnerId,
        UUID unitId,
        PracticeMode mode,
        List<PracticeMode> modes,
        Integer limit,
        String idempotencyKey
    ) {
        return startSession(learnerId, unitId, mode, modes, limit, null, idempotencyKey);
    }

    @Transactional
    public SessionView startSession(
        UUID learnerId,
        UUID unitId,
        PracticeMode mode,
        List<PracticeMode> modes,
        Integer limit,
        List<UUID> entryIds,
        String idempotencyKey
    ) {
        ModeSelection selection = resolveModeSelection(mode, modes);
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new RequestValidationException("limit must be between 1 and 100");
        }
        List<UUID> normalizedEntryIds = normalizeRequestedEntryIds(entryIds);

        String fingerprint = "UNIT|" + unitId + "|" + selection.fingerprint() + "|" + limit
            + "|" + entryIdsFingerprint(normalizedEntryIds);
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
                List<StudyEntrySource> allSources = entryRows.stream().map(VocabularyStudyService::toSource).toList();
                List<StudyEntrySource> targetSources = filterSourcesByEntryIds(allSources, normalizedEntryIds);

                UUID sessionId = UUID.randomUUID();
                long seed = sessionId.getMostSignificantBits() ^ sessionId.getLeastSignificantBits();
                int requestedLimit = limit != null ? limit : Math.max(1, targetSources.size());
                List<SnapshotQuestion> questions = selection.multiMode()
                    ? generator.generateMixedQuestions(targetSources, allSources, selection.concreteModes(), requestedLimit, seed)
                    : generator.generateQuestions(targetSources, allSources, selection.sessionMode(), requestedLimit, seed);
                if (questions.isEmpty()) {
                    throw new RequestValidationException(
                        "No eligible questions available for mode " + selection.sessionMode()
                    );
                }

                SessionSnapshot snapshot = new SessionSnapshot(
                    unit.packId(),
                    unit.id(),
                    unit.title(),
                    unit.level(),
                    unit.revision(),
                    selection.sessionMode(),
                    questions
                );
                Instant now = Instant.now();
                Instant expiresAt = now.plusSeconds(SESSION_TTL_SECONDS);
                repository.insertSession(
                    sessionId,
                    learnerId,
                    unit.packId(),
                    unit.id(),
                    selection.sessionMode(),
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

    @Transactional
    public SessionView startPackSession(
        UUID learnerId,
        UUID packId,
        PracticeMode mode,
        List<PracticeMode> modes,
        Integer limit,
        String idempotencyKey
    ) {
        return startPackSession(learnerId, packId, mode, modes, limit, null, idempotencyKey);
    }

    @Transactional
    public SessionView startPackSession(
        UUID learnerId,
        UUID packId,
        PracticeMode mode,
        List<PracticeMode> modes,
        Integer limit,
        List<UUID> entryIds,
        String idempotencyKey
    ) {
        ModeSelection selection = resolveModeSelection(mode, modes);
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new RequestValidationException("limit must be between 1 and 100");
        }
        List<UUID> normalizedEntryIds = normalizeRequestedEntryIds(entryIds);

        String fingerprint = "PACK|" + packId + "|" + selection.fingerprint() + "|" + limit
            + "|" + entryIdsFingerprint(normalizedEntryIds);
        return executeIdempotent(
            learnerId,
            "vocabulary.study.start",
            idempotencyKey,
            fingerprint,
            SessionView.class,
            () -> {
                PackRow pack = repository.findVisiblePack(packId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vocabulary pack not found"));
                requirePackEntitlement(learnerId, pack.accessMode(), pack.requiredFeatureKey());

                List<EntryWithProgressRow> entryRows = repository.findVisibleEntriesByPack(packId, learnerId);
                List<StudyEntrySource> allSources = entryRows.stream().map(VocabularyStudyService::toSource).toList();
                List<StudyEntrySource> targetSources = new ArrayList<>(
                    filterSourcesByEntryIds(allSources, normalizedEntryIds)
                );

                UUID sessionId = UUID.randomUUID();
                long seed = sessionId.getMostSignificantBits() ^ sessionId.getLeastSignificantBits();
                int requestedLimit = limit != null ? limit : Math.min(30, Math.max(1, targetSources.size()));

                List<SnapshotQuestion> questions;
                if (selection.multiMode()) {
                    questions = generator.generateMixedQuestions(
                        targetSources,
                        allSources,
                        selection.concreteModes(),
                        requestedLimit,
                        seed
                    );
                } else {
                    Collections.shuffle(targetSources, new Random(seed));
                    questions = generator.generateQuestions(
                        targetSources,
                        allSources,
                        selection.sessionMode(),
                        requestedLimit,
                        seed
                    );
                }
                if (questions.isEmpty()) {
                    throw new RequestValidationException(
                        "No eligible questions available for mode " + selection.sessionMode()
                    );
                }

                String packTitle = pack.titleVi() != null && !pack.titleVi().isBlank() ? pack.titleVi() : pack.titleEn();
                SessionSnapshot snapshot = new SessionSnapshot(
                    pack.id(),
                    null,
                    packTitle,
                    null,
                    0L,
                    selection.sessionMode(),
                    questions
                );
                Instant now = Instant.now();
                Instant expiresAt = now.plusSeconds(SESSION_TTL_SECONDS);
                repository.insertSession(
                    sessionId,
                    learnerId,
                    pack.id(),
                    null,
                    selection.sessionMode(),
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

    private static List<UUID> normalizeRequestedEntryIds(List<UUID> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID entryId : entryIds) {
            if (entryId == null) {
                throw new RequestValidationException("entryIds cannot contain null");
            }
            unique.add(entryId);
        }
        if (unique.size() > 100) {
            throw new RequestValidationException("entryIds cannot exceed 100 entries");
        }
        return List.copyOf(unique);
    }

    private static String entryIdsFingerprint(List<UUID> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return "";
        }
        return entryIds.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(","));
    }

    private static List<StudyEntrySource> filterSourcesByEntryIds(
        List<StudyEntrySource> allSources,
        List<UUID> requestedEntryIds
    ) {
        if (requestedEntryIds == null || requestedEntryIds.isEmpty()) {
            return allSources;
        }
        Map<UUID, StudyEntrySource> byId = new LinkedHashMap<>();
        for (StudyEntrySource source : allSources) {
            byId.put(source.id(), source);
        }
        List<StudyEntrySource> selected = new ArrayList<>(requestedEntryIds.size());
        for (UUID id : requestedEntryIds) {
            StudyEntrySource match = byId.get(id);
            if (match == null) {
                throw new RequestValidationException("Requested vocabulary entryId is outside this scope: " + id);
            }
            selected.add(match);
        }
        return selected;
    }

    public List<SessionSummary> listSessions(UUID learnerId, UUID unitId, String status) {
        return listSessionsPage(learnerId, null, unitId, status, 0, 100).items();
    }

    public SessionPage listSessionsPage(UUID learnerId, UUID unitId, String status, int page, int size) {
        return listSessionsPage(learnerId, null, unitId, status, page, size);
    }

    public SessionPage listSessionsPage(UUID learnerId, UUID packId, UUID unitId, String status, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new RequestValidationException("page must be between 0 and 100000 and size between 1 and 100");
        }
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !Set.of("OPEN", "SUBMITTED", "EXPIRED").contains(normalizedStatus)) {
            throw new RequestValidationException("status must be OPEN, SUBMITTED, or EXPIRED");
        }
        long total = repository.countSessions(learnerId, packId, unitId, normalizedStatus);
        List<SessionSummary> items = total == 0
            ? List.of()
            : repository.findSessionsPage(learnerId, packId, unitId, normalizedStatus, page * size, size).stream()
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
    public HintResponse requestHint(UUID learnerId, UUID sessionId, UUID questionId) {
        SessionRow row = repository.findSession(sessionId, learnerId, true)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary study session not found"));
        requireOpen(row);

        SessionSnapshot snapshot = readSnapshot(row);
        SnapshotQuestion q = snapshot.questions().stream()
            .filter(item -> item.id().equals(questionId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Question not found in session"));

        if (q.mode() != PracticeMode.GUESS_WORD) {
            throw new RequestValidationException("Hints are only supported for GUESS_WORD questions");
        }

        Map<UUID, QuestionState> states = new LinkedHashMap<>(readQuestionStates(row));
        QuestionState current = states.getOrDefault(questionId, QuestionState.EMPTY);
        if (current.checked()) {
            throw new StateConflictException("Question has already been checked");
        }

        int maxHints = q.maxHints() != null ? q.maxHints() : 0;
        List<Integer> revealed = new ArrayList<>(
            current.revealedPositions() != null ? current.revealedPositions() : List.of()
        );
        if (revealed.size() >= maxHints) {
            throw new RequestValidationException("No hints remaining for this question");
        }
        OptionalInt nextPos = VocabularyExerciseGenerator.nextRevealPosition(q.baseTerm(), revealed);
        if (nextPos.isEmpty()) {
            throw new RequestValidationException("No hints remaining for this question");
        }
        revealed.add(nextPos.getAsInt());
        Collections.sort(revealed);

        List<Integer> immutableRevealed = List.copyOf(revealed);
        states.put(questionId, new QuestionState(immutableRevealed, false, false));
        repository.updateQuestionState(sessionId, json.writeValueAsString(states));

        return new HintResponse(
            questionId,
            VocabularyExerciseGenerator.maskWithRevealed(q.baseTerm(), immutableRevealed),
            immutableRevealed,
            immutableRevealed.size(),
            Math.max(0, maxHints - immutableRevealed.size())
        );
    }

    @Transactional
    public CheckQuestionResponse checkQuestion(
        UUID learnerId,
        UUID sessionId,
        UUID questionId,
        long revision,
        String answer,
        boolean giveUp
    ) {
        SessionRow row = repository.findSession(sessionId, learnerId, true)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary study session not found"));
        requireOpenAndRevision(row, revision);

        SessionSnapshot snapshot = readSnapshot(row);
        SnapshotQuestion q = snapshot.questions().stream()
            .filter(item -> item.id().equals(questionId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Question not found in session"));

        Map<UUID, String> answers = new LinkedHashMap<>(readAnswers(row));
        Map<UUID, QuestionState> states = new LinkedHashMap<>(readQuestionStates(row));
        QuestionState currentState = states.getOrDefault(questionId, QuestionState.EMPTY);
        List<Integer> revealed = currentState.revealedPositions() != null
            ? List.copyOf(currentState.revealedPositions())
            : List.of();
        int hintsUsed = revealed.size();

        if (currentState.checked()) {
            String storedAnswer = answers.get(questionId);
            boolean correct = !currentState.gaveUp() && grader.isCorrect(q, storedAnswer);
            return new CheckQuestionResponse(
                row.revision(),
                q.id(),
                q.entryId(),
                q.prompt(),
                q.baseTerm(),
                storedAnswer,
                q.correctAnswer(),
                correct,
                hintsUsed,
                currentState.gaveUp()
            );
        }

        if (!giveUp && answer == null) {
            throw new RequestValidationException("answer is required unless giveUp is true");
        }
        if (!giveUp && (q.mode() == PracticeMode.CHOOSE_MEANING || q.mode() == PracticeMode.CHOOSE_WORD)
            && q.options() != null
            && q.options().stream().noneMatch(o -> o.key().equals(answer))) {
            throw new RequestValidationException("Answer must match a valid option key for question " + q.id());
        }

        String recordedAnswer = giveUp ? null : answer;
        if (giveUp) {
            answers.remove(questionId);
        } else {
            answers.put(questionId, answer);
        }
        boolean correct = !giveUp && grader.isCorrect(q, answer);
        states.put(questionId, new QuestionState(revealed, true, giveUp));

        int updated = repository.updateAnswersAndQuestionState(
            sessionId,
            revision,
            json.writeValueAsString(answers),
            json.writeValueAsString(states)
        );
        if (updated != 1) {
            throw new StaleRevisionException("Session revision mismatch", row.revision());
        }

        return new CheckQuestionResponse(
            revision + 1,
            q.id(),
            q.entryId(),
            q.prompt(),
            q.baseTerm(),
            recordedAnswer,
            q.correctAnswer(),
            correct,
            hintsUsed,
            giveUp
        );
    }

    @Transactional
    public SessionView saveAnswers(UUID learnerId, UUID sessionId, long revision, Map<UUID, String> answers) {
        SessionRow row = repository.findSession(sessionId, learnerId, true)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary study session not found"));
        requireOpenAndRevision(row, revision);

        SessionSnapshot snapshot = readSnapshot(row);
        validateAnswers(snapshot, answers);

        Map<UUID, String> existingAnswers = readAnswers(row);
        Map<UUID, QuestionState> states = readQuestionStates(row);
        Map<UUID, String> mergedAnswers = new LinkedHashMap<>(answers);

        for (SnapshotQuestion q : snapshot.questions()) {
            QuestionState state = states.get(q.id());
            if (state != null && state.checked()) {
                if (state.gaveUp()) {
                    if (answers.containsKey(q.id())) {
                        throw new StateConflictException("Cannot answer a question after giving up: " + q.id());
                    }
                    mergedAnswers.remove(q.id());
                } else {
                    String lockedAnswer = existingAnswers.get(q.id());
                    if (answers.containsKey(q.id()) && !Objects.equals(answers.get(q.id()), lockedAnswer)) {
                        throw new StateConflictException("Cannot modify an already checked question: " + q.id());
                    }
                    if (lockedAnswer != null) {
                        mergedAnswers.put(q.id(), lockedAnswer);
                    }
                }
            }
        }

        int updated = repository.updateAnswers(sessionId, revision, json.writeValueAsString(mergedAnswers));
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
                Map<UUID, QuestionState> states = readQuestionStates(row);
                List<QuestionFeedback> feedback = new ArrayList<>(snapshot.questions().size());
                int correctCount = 0;
                Instant now = Instant.now();

                for (SnapshotQuestion q : snapshot.questions()) {
                    QuestionState state = states.getOrDefault(q.id(), QuestionState.EMPTY);
                    int hintsUsed = state.revealedPositions() != null ? state.revealedPositions().size() : 0;
                    boolean gaveUp = state.gaveUp();
                    String learnerAnswer = gaveUp ? null : answers.get(q.id());
                    boolean correct = !gaveUp && grader.isCorrect(q, learnerAnswer);
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
                        correct,
                        hintsUsed,
                        gaveUp
                    ));
                    UUID entryUnitId = q.unitId() != null ? q.unitId() : row.unitId();
                    if (entryUnitId != null) {
                        repository.upsertEntryProgress(learnerId, q.entryId(), entryUnitId, row.packId(), correct, now);
                    }
                }

                int totalCount = snapshot.questions().size();
                int accuracy = totalCount == 0 ? 0 : (int) Math.round((correctCount * 100.0) / totalCount);
                SessionResult result = new SessionResult(correctCount, totalCount, accuracy, List.copyOf(feedback));

                int updated = repository.markSubmitted(sessionId, revision, json.writeValueAsString(result), now);
                if (updated != 1) {
                    throw new StaleRevisionException("Session revision mismatch", row.revision());
                }

                List<UUID> completedEntryIds = feedback.stream()
                    .map(QuestionFeedback::entryId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

                events.publishEvent(new VocabularyUnitCompletedEvent(
                    sessionId,
                    learnerId,
                    row.packId(),
                    row.unitId(),
                    correctCount,
                    totalCount,
                    now,
                    completedEntryIds
                ));

                return repository.findSession(sessionId, learnerId, false)
                    .map(this::toView)
                    .orElseThrow();
            }
        );
    }

    private FlashcardItem toFlashcardItem(FlashcardEntryRow row, Instant now) {
        String srsState = classifySrsState(row.srsReviewCount(), row.srsNextReviewAt(), row.srsStability(), now);
        Map<SpacedRepetitionScheduler.Rating, IntervalPreview> previews = computeIntervalPreviews(
            row.srsStability(),
            row.srsDifficulty(),
            row.srsReviewCount(),
            row.srsLapseCount(),
            now
        );
        return new FlashcardItem(
            row.entryId(),
            row.unitId(),
            row.unitLevel(),
            row.position(),
            row.term(),
            row.revision(),
            row.content(),
            row.imageId(),
            row.ukAudioId(),
            row.usAudioId(),
            row.exampleAudioId(),
            row.secondExampleAudioId(),
            entryMediaUrl(row.entryId(), row.imageId()),
            entryMediaUrl(row.entryId(), row.ukAudioId()),
            entryMediaUrl(row.entryId(), row.usAudioId()),
            entryMediaUrl(row.entryId(), row.exampleAudioId()),
            entryMediaUrl(row.entryId(), row.secondExampleAudioId()),
            srsState,
            row.srsReviewCount(),
            row.srsLapseCount(),
            row.srsStability(),
            row.srsDifficulty(),
            row.srsNextReviewAt(),
            previews
        );
    }

    private Map<SpacedRepetitionScheduler.Rating, IntervalPreview> computeIntervalPreviews(
        double stability,
        double difficulty,
        int reviewCount,
        int lapseCount,
        Instant now
    ) {
        if (scheduler == null) {
            return Map.of();
        }
        Map<SpacedRepetitionScheduler.Rating, IntervalPreview> previews = new EnumMap<>(SpacedRepetitionScheduler.Rating.class);
        for (SpacedRepetitionScheduler.Rating rating : SpacedRepetitionScheduler.Rating.values()) {
            var res = scheduler.schedule(stability, difficulty, reviewCount, lapseCount, rating, now);
            long days = Math.max(1L, Duration.between(now, res.nextReviewAt()).toDays());
            previews.put(rating, new IntervalPreview(res.nextReviewAt(), days));
        }
        return previews;
    }

    private static String classifySrsState(
        int reviewCount,
        Instant nextReviewAt,
        double stability,
        Instant now
    ) {
        if (reviewCount == 0) {
            return "NEW";
        }
        if (nextReviewAt == null || !nextReviewAt.isAfter(now)) {
            return "DUE";
        }
        return stability >= 5.0 ? "MASTERED" : "LEARNING";
    }

    private static String entryMediaUrl(UUID entryId, UUID assetId) {
        return assetId != null ? "/api/v1/vocabulary/entries/" + entryId + "/media/" + assetId : null;
    }

    private record ModeSelection(PracticeMode sessionMode, List<PracticeMode> concreteModes, boolean multiMode) {
        String fingerprint() {
            if (!multiMode) {
                return sessionMode.name();
            }
            return sessionMode.name() + ":" + concreteModes.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse("");
        }
    }

    private static ModeSelection resolveModeSelection(PracticeMode mode, List<PracticeMode> modes) {
        if (modes != null && !modes.isEmpty()) {
            List<PracticeMode> concrete = modes.stream()
                .filter(Objects::nonNull)
                .filter(m -> m != PracticeMode.MIXED)
                .distinct()
                .toList();
            if (concrete.isEmpty()) {
                throw new RequestValidationException("modes must contain at least one concrete practice mode");
            }
            PracticeMode effectiveSessionMode = concrete.size() == 1 ? concrete.getFirst() : PracticeMode.MIXED;
            return new ModeSelection(effectiveSessionMode, concrete, concrete.size() > 1);
        }
        if (mode == null) {
            throw new RequestValidationException("Practice mode is required");
        }
        if (mode == PracticeMode.MIXED) {
            return new ModeSelection(PracticeMode.MIXED, PracticeMode.STANDARD_PRACTICE_MODES, true);
        }
        return new ModeSelection(mode, List.of(mode), false);
    }

    private void requirePackEntitlement(UUID learnerId, String accessMode, String featureKey) {
        if ("FEATURE".equals(accessMode) && featureKey != null) {
            entitlements.requireFeature(learnerId, FeatureKey.of(featureKey));
        }
    }

    private void requireOpen(SessionRow row) {
        if (row.submittedAt() != null) {
            throw new StateConflictException("Session is already submitted");
        }
        if (!row.expiresAt().isAfter(Instant.now())) {
            throw new SessionExpiredException("Session expired", row.expiresAt());
        }
    }

    private void requireOpenAndRevision(SessionRow row, long expectedRevision) {
        requireOpen(row);
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
        Map<UUID, QuestionState> states = readQuestionStates(row);
        List<QuestionView> questionViews = snapshot.questions().stream()
            .map(q -> QuestionView.from(q, states.get(q.id())))
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

    private Map<UUID, QuestionState> readQuestionStates(SessionRow row) {
        if (row.questionStateJson() == null || row.questionStateJson().isBlank()) {
            return Map.of();
        }
        return json.readValue(row.questionStateJson(), new TypeReference<>() {});
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
