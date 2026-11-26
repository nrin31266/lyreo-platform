package com.lyreo.chat.api;

import com.lyreo.chat.application.SpeakingRoleplayService;
import com.lyreo.chat.domain.SpeakingPreviewTurnResult;
import com.lyreo.chat.domain.SpeakingScenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD & live AI roleplay testing for conversational voice roleplay scenarios. */
@RestController
@RequestMapping("/api/v1/admin/speaking/scenarios")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSpeakingScenarioController {
    private final SpeakingRoleplayService roleplay;

    public AdminSpeakingScenarioController(SpeakingRoleplayService roleplay) {
        this.roleplay = roleplay;
    }

    @GetMapping
    public List<SpeakingScenario> list() {
        return roleplay.listAllScenarios();
    }

    @GetMapping("/{id}")
    public SpeakingScenario get(@PathVariable UUID id) {
        return roleplay.getScenario(id);
    }

    @PostMapping
    public ResponseEntity<SpeakingScenario> create(@Valid @RequestBody SaveScenarioRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roleplay.createScenario(toCommand(req)));
    }

    @PutMapping("/{id}")
    public SpeakingScenario update(@PathVariable UUID id, @Valid @RequestBody SaveScenarioRequest req) {
        return roleplay.updateScenario(id, toCommand(req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        roleplay.deleteScenario(id);
    }

    @PostMapping("/{id}/preview-turn")
    public SpeakingPreviewTurnResult previewTurn(
        @PathVariable UUID id,
        @Valid @RequestBody PreviewTurnRequest request
    ) {
        return roleplay.previewTurn(id, request.history(), request.userMessage());
    }

    private static SpeakingRoleplayService.SaveScenarioCommand toCommand(SaveScenarioRequest req) {
        return new SpeakingRoleplayService.SaveScenarioCommand(
            req.code(),
            req.title(),
            req.titleVi(),
            req.category(),
            req.cefrLevel(),
            req.coverAssetId(),
            req.aiRoleName(),
            req.aiPersona(),
            req.learnerRole(),
            req.settingDescription(),
            req.openingLine(),
            req.systemPrompt(),
            req.objectives(),
            req.ttsVoice(),
            req.targetTurns() == null ? 6 : req.targetTurns(),
            req.estimatedMinutes() == null ? 5 : req.estimatedMinutes(),
            req.accessMode(),
            req.requiredFeatureKey(),
            req.status()
        );
    }

    public record SaveScenarioRequest(
        @NotBlank String code,
        @NotBlank String title,
        String titleVi,
        @NotBlank String category,
        @NotBlank String cefrLevel,
        UUID coverAssetId,
        @NotBlank String aiRoleName,
        @NotBlank String aiPersona,
        @NotBlank String learnerRole,
        @NotBlank String settingDescription,
        @NotBlank String openingLine,
        String systemPrompt,
        List<String> objectives,
        String ttsVoice,
        Integer targetTurns,
        Integer estimatedMinutes,
        String accessMode,
        String requiredFeatureKey,
        String status
    ) {}

    public record PreviewTurnRequest(
        List<SpeakingRoleplayService.PreviewMessage> history,
        @NotBlank String userMessage
    ) {}
}
