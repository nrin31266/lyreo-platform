package com.lyreo.chat.application.port;

import com.lyreo.chat.domain.SpeakingScenario;
import com.lyreo.chat.domain.SpeakingSessionDetail;
import com.lyreo.chat.domain.SpeakingTurn;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SpeakingScenarioRepository {
    List<SpeakingScenario> listAllScenarios();

    List<SpeakingScenario> listPublishedScenarios();

    Optional<SpeakingScenario> findScenarioById(UUID id);

    boolean existsByCode(String code, UUID excludeId);

    SpeakingScenario insertScenario(SpeakingScenario scenario);

    Optional<SpeakingScenario> updateScenario(SpeakingScenario scenario);

    boolean deleteScenario(UUID id);

    SpeakingSessionDetail createSession(UUID sessionId, UUID scenarioId, UUID learnerId, String openingLine);

    Optional<SpeakingSessionDetail> findOwnedSession(UUID sessionId, UUID learnerId);

    SpeakingTurn appendTurn(UUID sessionId, String role, String textContent, UUID audioAssetId, UUID aiInvocationId);

    Optional<SpeakingSessionDetail> completeSession(
        UUID sessionId,
        UUID learnerId,
        List<String> completedObjectives,
        Map<String, Object> feedback
    );
}
