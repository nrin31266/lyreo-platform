package com.lyreo.toeic.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.toeic.application.ToeicAttemptService;
import com.lyreo.toeic.application.ToeicTestQueryService;
import com.lyreo.toeic.domain.ToeicTestContent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/toeic/tests")
public class ToeicAttemptController {
    private final ToeicAttemptService attempts;
    private final ToeicTestQueryService tests;
    private final AppUserProvisioningService users;

    public ToeicAttemptController(
        ToeicAttemptService attempts,
        ToeicTestQueryService tests,
        AppUserProvisioningService users
    ) {
        this.attempts = attempts;
        this.tests = tests;
        this.users = users;
    }

    @GetMapping("/{catalogId}")
    public ToeicTestResponse getTest(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID catalogId
    ) {
        UUID learnerId = users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
        return ToeicTestResponse.from(tests.getActiveTest(learnerId, catalogId));
    }

    @PostMapping("/{catalogId}/attempts")
    public ToeicSubmitResponse submit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID catalogId,
        @Valid @RequestBody SubmitRequest request
    ) {
        UUID learnerId = users.provision(
            jwt.getSubject(),
            jwt.getClaimAsString("email")
        ).id();
        ToeicAttemptService.Mode serviceMode = ToeicAttemptService.Mode.valueOf(request.mode().name());
        var result = attempts.submit(learnerId, catalogId, serviceMode, request.answers());
        var score = result.score();
        return new ToeicSubmitResponse(
            result.attemptId(),
            new ToeicScoreResponse(
                score.listeningCorrect(),
                score.listeningTotal(),
                score.readingCorrect(),
                score.readingTotal(),
                score.listeningScaledScore(),
                score.readingScaledScore()
            )
        );
    }

    public enum AttemptMode {
        FULL_TEST,
        DRILL
    }

    /** In DRILL mode, answer-map keys are placement IDs in the active test version. */
    public record SubmitRequest(
        @NotNull(message = "mode is required")
        AttemptMode mode,
        Map<UUID, String> answers
    ) {}

    public record ToeicScoreResponse(
        int listeningCorrect,
        int listeningTotal,
        int readingCorrect,
        int readingTotal,
        Integer listeningScaledScore,
        Integer readingScaledScore
    ) {}

    public record ToeicSubmitResponse(
        UUID attemptId,
        ToeicScoreResponse score
    ) {}

    public record ToeicTestResponse(
        UUID catalogId,
        UUID testVersionId,
        ToeicTestMetadataResponse test,
        List<ToeicStimulusGroupResponse> groups,
        List<ToeicPlacementResponse> placements
    ) {
        public static ToeicTestResponse from(ToeicTestContent content) {
            return new ToeicTestResponse(
                content.catalogId(),
                content.testVersionId(),
                ToeicTestMetadataResponse.from(content.test()),
                content.groups().stream().map(ToeicStimulusGroupResponse::from).toList(),
                content.placements().stream().map(ToeicPlacementResponse::from).toList()
            );
        }
    }

    public record ToeicTestMetadataResponse(
        String name,
        int year,
        int testNumber,
        UUID setId,
        Integer orderIndex,
        String sourceLabel,
        Integer listeningDurationSeconds,
        Integer readingDurationSeconds,
        int totalQuestions,
        Integer difficultyLevel,
        Integer mediaVersion
    ) {
        private static ToeicTestMetadataResponse from(ToeicTestContent.TestMetadata metadata) {
            return new ToeicTestMetadataResponse(
                metadata.name(), metadata.year(), metadata.testNumber(), metadata.setId(),
                metadata.orderIndex(), metadata.sourceLabel(), metadata.listeningDurationSeconds(),
                metadata.readingDurationSeconds(), metadata.totalQuestions(), metadata.difficultyLevel(),
                metadata.mediaVersion()
            );
        }
    }

    public record ToeicStimulusGroupResponse(
        UUID id,
        int part,
        String kind,
        String title,
        Integer orderIndex,
        Integer difficultyLevel,
        List<Integer> questionNumbers,
        String renderHtml,
        String documentParseStatus,
        int documentCount,
        List<ToeicDocumentResponse> documents,
        List<ToeicMediaResponse> media
    ) {
        private static ToeicStimulusGroupResponse from(ToeicTestContent.StimulusGroup group) {
            return new ToeicStimulusGroupResponse(
                group.id(), group.part(), group.kind(), group.title(), group.orderIndex(),
                group.difficultyLevel(), group.questionNumbers(), group.renderHtml(),
                group.documentParseStatus(), group.documentCount(),
                group.documents().stream().map(ToeicDocumentResponse::from).toList(),
                group.media().stream().map(ToeicMediaResponse::from).toList()
            );
        }
    }

    public record ToeicDocumentResponse(
        UUID id,
        int ordinal,
        String documentType,
        String html,
        String parseStatus
    ) {
        private static ToeicDocumentResponse from(ToeicTestContent.Document document) {
            return new ToeicDocumentResponse(
                document.id(), document.ordinal(), document.documentType(), document.html(), document.parseStatus()
            );
        }
    }

    public record ToeicPlacementResponse(
        UUID id,
        UUID groupId,
        String section,
        int part,
        int questionNumber,
        Integer gapNumber,
        int orderIndex,
        ToeicItemResponse item
    ) {
        private static ToeicPlacementResponse from(ToeicTestContent.Placement placement) {
            return new ToeicPlacementResponse(
                placement.id(), placement.groupId(), placement.section(), placement.part(),
                placement.questionNumber(), placement.gapNumber(), placement.orderIndex(),
                ToeicItemResponse.from(placement.item())
            );
        }
    }

    public record ToeicItemResponse(
        UUID id,
        String kind,
        String stemEn,
        List<ToeicOptionResponse> options,
        Integer difficultyLevel,
        List<ToeicMediaResponse> media
    ) {
        private static ToeicItemResponse from(ToeicTestContent.Item item) {
            return new ToeicItemResponse(
                item.id(), item.kind(), item.stemEn(),
                item.options().stream().map(ToeicOptionResponse::from).toList(),
                item.difficultyLevel(), item.media().stream().map(ToeicMediaResponse::from).toList()
            );
        }
    }

    public record ToeicOptionResponse(String key, String text) {
        private static ToeicOptionResponse from(ToeicTestContent.Option option) {
            return new ToeicOptionResponse(option.key(), option.text());
        }
    }

    public record ToeicMediaResponse(
        String packageAssetId,
        String role,
        Integer mediaVersion,
        String storageObjectKey,
        String mimeType,
        long sizeBytes
    ) {
        private static ToeicMediaResponse from(ToeicTestContent.MediaAsset media) {
            return new ToeicMediaResponse(
                media.packageAssetId(), media.role(), media.mediaVersion(), media.storageObjectKey(),
                media.mimeType(), media.sizeBytes()
            );
        }
    }
}
