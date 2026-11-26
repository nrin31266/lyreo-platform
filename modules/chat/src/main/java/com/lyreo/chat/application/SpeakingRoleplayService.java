package com.lyreo.chat.application;

import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.chat.application.port.SpeakingScenarioRepository;
import com.lyreo.chat.domain.SpeakingPreviewTurnResult;
import com.lyreo.chat.domain.SpeakingScenario;
import com.lyreo.chat.domain.SpeakingSessionDetail;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Manages real-world AI Voice Roleplay scenarios, live admin testing, and learner sessions. */
public class SpeakingRoleplayService {
    public static final FeatureKey FEATURE_SPEAKING_ROLEPLAY = FeatureKey.of("speaking.roleplay");
    private static final Set<String> VALID_CEFR = Set.of("A1", "A2", "B1", "B2", "C1", "C2");
    private static final Set<String> VALID_STATUS = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private final SpeakingScenarioRepository repository;
    private final AiInvocationService ai;
    private final EntitlementService entitlements;

    public SpeakingRoleplayService(
        SpeakingScenarioRepository repository,
        AiInvocationService ai,
        EntitlementService entitlements
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ai = Objects.requireNonNull(ai, "ai");
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public List<SpeakingScenario> listAllScenarios() {
        return repository.listAllScenarios();
    }

    @Transactional(readOnly = true)
    public List<SpeakingScenarioView> listPublishedForLearner(UUID learnerId) {
        List<SpeakingScenario> scenarios = repository.listPublishedScenarios();
        List<SpeakingScenarioView> views = new ArrayList<>(scenarios.size());
        for (SpeakingScenario s : scenarios) {
            boolean locked = entitlements != null
                && "FEATURE".equals(s.accessMode())
                && s.requiredFeatureKey() != null
                && !entitlements.hasFeature(learnerId, FeatureKey.of(s.requiredFeatureKey()));
            views.add(new SpeakingScenarioView(s, locked));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public SpeakingScenario getScenario(UUID id) {
        return repository.findScenarioById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Speaking scenario not found"));
    }

    @Transactional
    public SpeakingScenario createScenario(SaveScenarioCommand cmd) {
        SpeakingScenario validated = validateCommand(UUID.randomUUID(), cmd);
        if (repository.existsByCode(validated.code(), null)) {
            throw new StateConflictException("Speaking scenario code already exists: " + validated.code());
        }
        return repository.insertScenario(validated);
    }

    @Transactional
    public SpeakingScenario updateScenario(UUID id, SaveScenarioCommand cmd) {
        if (repository.findScenarioById(id).isEmpty()) {
            throw new ResourceNotFoundException("Speaking scenario not found");
        }
        SpeakingScenario validated = validateCommand(id, cmd);
        if (repository.existsByCode(validated.code(), id)) {
            throw new StateConflictException("Speaking scenario code already exists: " + validated.code());
        }
        return repository.updateScenario(validated)
            .orElseThrow(() -> new ResourceNotFoundException("Speaking scenario not found"));
    }

    @Transactional
    public void deleteScenario(UUID id) {
        if (!repository.deleteScenario(id)) {
            throw new ResourceNotFoundException("Speaking scenario not found");
        }
    }

    public SpeakingPreviewTurnResult previewTurn(
        UUID scenarioId,
        List<PreviewMessage> history,
        String userMessage
    ) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new RequestValidationException("userMessage is required");
        }
        SpeakingScenario scenario = getScenario(scenarioId);
        return executeRoleplayTurn(scenario, history == null ? List.of() : history, userMessage.trim());
    }

    @Transactional
    public SpeakingSessionDetail startSession(UUID learnerId, UUID scenarioId) {
        SpeakingScenario scenario = repository.findScenarioById(scenarioId)
            .filter(s -> "PUBLISHED".equals(s.status()))
            .orElseThrow(() -> new ResourceNotFoundException("Speaking scenario not found"));
        enforceScenarioAccess(learnerId, scenario);
        if (entitlements != null) {
            entitlements.consumeQuota(learnerId, FEATURE_SPEAKING_ROLEPLAY, "roleplay-start-" + UUID.randomUUID());
        }
        return repository.createSession(UUID.randomUUID(), scenario.id(), learnerId, scenario.openingLine());
    }

    @Transactional(readOnly = true)
    public SpeakingSessionDetail getSession(UUID learnerId, UUID sessionId) {
        return repository.findOwnedSession(sessionId, learnerId)
            .orElseThrow(() -> new ResourceNotFoundException("Speaking session not found"));
    }

    @Transactional
    public SpeakingSessionDetail sendLearnerTurn(UUID learnerId, UUID sessionId, String userText, UUID audioAssetId) {
        if (userText == null || userText.isBlank()) {
            throw new RequestValidationException("text is required");
        }
        SpeakingSessionDetail session = getSession(learnerId, sessionId);
        if ("COMPLETED".equals(session.status())) {
            throw new StateConflictException("Speaking session is already completed");
        }
        SpeakingScenario scenario = getScenario(session.scenarioId());
        enforceScenarioAccess(learnerId, scenario);

        repository.appendTurn(sessionId, "USER", userText.trim(), audioAssetId, null);

        List<PreviewMessage> history = session.turns().stream()
            .map(t -> new PreviewMessage(t.role().toLowerCase(Locale.ROOT), t.textContent()))
            .toList();
        SpeakingPreviewTurnResult reply = executeRoleplayTurn(scenario, history, userText.trim());
        repository.appendTurn(sessionId, "ASSISTANT", reply.assistantReply(), null, reply.aiInvocationId());

        return getSession(learnerId, sessionId);
    }

    @Transactional
    public SpeakingSessionDetail completeSession(UUID learnerId, UUID sessionId) {
        SpeakingSessionDetail session = getSession(learnerId, sessionId);
        SpeakingScenario scenario = getScenario(session.scenarioId());
        long learnerTurns = session.turns().stream().filter(t -> "USER".equals(t.role())).count();
        List<String> doneObjectives = new ArrayList<>();
        if (scenario.objectives() != null) {
            int limit = (int) Math.min(scenario.objectives().size(), Math.max(1, learnerTurns));
            for (int i = 0; i < limit; i++) {
                doneObjectives.add(scenario.objectives().get(i));
            }
        }
        Map<String, Object> feedback = Map.of(
            "learnerTurns", learnerTurns,
            "targetTurns", scenario.targetTurns(),
            "cefrLevel", scenario.cefrLevel(),
            "summary", "Completed real-world roleplay scenario \"" + scenario.title() + "\" as " + scenario.learnerRole() + "."
        );
        return repository.completeSession(sessionId, learnerId, doneObjectives, feedback)
            .orElseThrow(() -> new ResourceNotFoundException("Speaking session not found"));
    }

    private SpeakingPreviewTurnResult executeRoleplayTurn(
        SpeakingScenario scenario,
        List<PreviewMessage> history,
        String userMessage
    ) {
        String systemPrompt = buildRoleplaySystemPrompt(scenario);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("question", userMessage);
        input.put("scenarioCode", scenario.code());
        input.put("cefrLevel", scenario.cefrLevel());
        input.put("aiRoleName", scenario.aiRoleName());
        input.put("learnerRole", scenario.learnerRole());
        input.put("objectives", scenario.objectives());
        if (!history.isEmpty()) {
            List<Map<String, String>> recent = history.stream()
                .skip(Math.max(0, history.size() - 8))
                .map(m -> Map.of(
                    "role", m.role() == null ? "user" : m.role(),
                    "content", m.content() == null ? "" : m.content()
                ))
                .toList();
            input.put("history", recent);
        }

        AiExecutionResult result = ai.execute(
            AiCapability.GENERAL_LLM,
            systemPrompt,
            input,
            Map.of("response_format", "text")
        );

        String reply = extractAssistantReply(result, scenario, userMessage, history.size());
        List<String> tips = scenario.objectives() == null ? List.of() : scenario.objectives();
        return new SpeakingPreviewTurnResult(
            reply,
            scenario.ttsVoice(),
            result.invocationId(),
            result.provider(),
            result.latencyMs(),
            tips
        );
    }

    private static String extractAssistantReply(
        AiExecutionResult result,
        SpeakingScenario scenario,
        String userMessage,
        int turnIndex
    ) {
        Object raw = result.output().get("content");
        if (raw == null) {
            raw = result.output().get("text");
        }
        String text = raw == null ? "" : raw.toString().trim();
        if (!text.isEmpty() && !text.startsWith("[mock-")) {
            return text;
        }
        String objectiveHint = (scenario.objectives() != null && !scenario.objectives().isEmpty())
            ? scenario.objectives().get(turnIndex % scenario.objectives().size())
            : "continue our conversation naturally";
        return String.format(
            Locale.ROOT,
            "I see — you mentioned \"%s\". As %s, let's work through the next detail together so you can %s. What would you prefer?",
            userMessage.length() > 80 ? userMessage.substring(0, 80) + "..." : userMessage,
            scenario.aiRoleName(),
            objectiveHint.toLowerCase(Locale.ROOT)
        );
    }

    private static String buildRoleplaySystemPrompt(SpeakingScenario s) {
        String custom = s.systemPrompt() == null ? "" : s.systemPrompt().trim();
        return """
            You are playing the role of %s (%s).
            The learner is playing: %s.
            Setting: %s.
            Target CEFR Level: %s.
            Keep replies natural, spoken-English friendly (2-3 concise sentences per turn), ask one clear follow-up question, and help the learner achieve these objectives: %s.
            Additional direction: %s
            """.formatted(
            s.aiRoleName(),
            s.aiPersona(),
            s.learnerRole(),
            s.settingDescription(),
            s.cefrLevel(),
            String.join("; ", s.objectives() == null ? List.of() : s.objectives()),
            custom
        ).trim();
    }

    private void enforceScenarioAccess(UUID learnerId, SpeakingScenario scenario) {
        if (entitlements != null && "FEATURE".equals(scenario.accessMode()) && scenario.requiredFeatureKey() != null) {
            entitlements.requireFeature(learnerId, FeatureKey.of(scenario.requiredFeatureKey()));
        }
    }

    private static SpeakingScenario validateCommand(UUID id, SaveScenarioCommand cmd) {
        String code = requireText(cmd.code(), "code", 64);
        String title = requireText(cmd.title(), "title", 200);
        String titleVi = cmd.titleVi() == null || cmd.titleVi().isBlank() ? title : cmd.titleVi().trim();
        String category = requireText(cmd.category(), "category", 64);
        String cefr = requireText(cmd.cefrLevel(), "cefrLevel", 8).toUpperCase(Locale.ROOT);
        if (!VALID_CEFR.contains(cefr)) {
            throw new RequestValidationException("cefrLevel must be one of A1, A2, B1, B2, C1, C2");
        }
        String aiRoleName = requireText(cmd.aiRoleName(), "aiRoleName", 120);
        String aiPersona = requireText(cmd.aiPersona(), "aiPersona", 2000);
        String learnerRole = requireText(cmd.learnerRole(), "learnerRole", 120);
        String setting = requireText(cmd.settingDescription(), "settingDescription", 2000);
        String openingLine = requireText(cmd.openingLine(), "openingLine", 1000);
        String systemPrompt = cmd.systemPrompt() == null ? "" : cmd.systemPrompt().trim();
        String ttsVoice = cmd.ttsVoice() == null || cmd.ttsVoice().isBlank() ? "af_heart" : cmd.ttsVoice().trim();
        int targetTurns = cmd.targetTurns() > 0 ? Math.min(30, Math.max(2, cmd.targetTurns())) : 6;
        int estMinutes = cmd.estimatedMinutes() > 0 ? Math.min(60, Math.max(1, cmd.estimatedMinutes())) : 5;
        String accessMode = (cmd.accessMode() == null || cmd.accessMode().isBlank())
            ? "PUBLIC"
            : cmd.accessMode().trim().toUpperCase(Locale.ROOT);
        if (!"PUBLIC".equals(accessMode) && !"FEATURE".equals(accessMode)) {
            throw new RequestValidationException("accessMode must be PUBLIC or FEATURE");
        }
        String featureKey = null;
        if ("FEATURE".equals(accessMode)) {
            featureKey = (cmd.requiredFeatureKey() == null || cmd.requiredFeatureKey().isBlank())
                ? FEATURE_SPEAKING_ROLEPLAY.value()
                : FeatureKey.of(cmd.requiredFeatureKey().trim()).value();
        }
        String status = (cmd.status() == null || cmd.status().isBlank())
            ? "DRAFT"
            : cmd.status().trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUS.contains(status)) {
            throw new RequestValidationException("status must be DRAFT, PUBLISHED, or ARCHIVED");
        }
        List<String> objectives = cmd.objectives() == null ? List.of() : cmd.objectives().stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();

        return new SpeakingScenario(
            id, code, title, titleVi, category, cefr, cmd.coverAssetId(),
            aiRoleName, aiPersona, learnerRole, setting, openingLine, systemPrompt,
            objectives, ttsVoice, targetTurns, estMinutes, accessMode, featureKey, status,
            null, null
        );
    }

    private static String requireText(String val, String field, int maxLen) {
        if (val == null || val.isBlank()) {
            throw new RequestValidationException(field + " is required");
        }
        String trimmed = val.trim();
        if (trimmed.length() > maxLen) {
            throw new RequestValidationException(field + " exceeds " + maxLen + " characters");
        }
        return trimmed;
    }

    public record SaveScenarioCommand(
        String code,
        String title,
        String titleVi,
        String category,
        String cefrLevel,
        UUID coverAssetId,
        String aiRoleName,
        String aiPersona,
        String learnerRole,
        String settingDescription,
        String openingLine,
        String systemPrompt,
        List<String> objectives,
        String ttsVoice,
        int targetTurns,
        int estimatedMinutes,
        String accessMode,
        String requiredFeatureKey,
        String status
    ) {}

    public record PreviewMessage(String role, String content) {}

    public record SpeakingScenarioView(SpeakingScenario scenario, boolean locked) {}
}
