package com.lyreo.gamification.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.gamification.application.GamificationQueryService;
import com.lyreo.gamification.application.MissionProgressService;
import com.lyreo.gamification.domain.AchievementDefinition;
import com.lyreo.gamification.domain.AchievementView;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.GamificationSummary;
import com.lyreo.gamification.domain.MissionView;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import com.lyreo.platform.content.AssetStore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/gamification")
public class GamificationController {
    private final GamificationQueryService queryService;
    private final AppUserProvisioningService users;
    private final AssetStore assets;
    private final MissionProgressService missionService;
    private final OperationIdempotencyService idempotency;

    public GamificationController(
        GamificationQueryService queryService,
        AppUserProvisioningService users
    ) {
        this(queryService, users, (AssetStore) null, (MissionProgressService) null, (OperationIdempotencyService) null);
    }

    @Autowired
    public GamificationController(
        GamificationQueryService queryService,
        AppUserProvisioningService users,
        ObjectProvider<AssetStore> assetsProvider,
        ObjectProvider<MissionProgressService> missionServiceProvider,
        ObjectProvider<OperationIdempotencyService> idempotencyProvider
    ) {
        this(
            queryService,
            users,
            assetsProvider.getIfAvailable(),
            missionServiceProvider.getIfAvailable(),
            idempotencyProvider.getIfAvailable()
        );
    }

    public GamificationController(
        GamificationQueryService queryService,
        AppUserProvisioningService users,
        AssetStore assets
    ) {
        this(queryService, users, assets, (MissionProgressService) null, (OperationIdempotencyService) null);
    }

    public GamificationController(
        GamificationQueryService queryService,
        AppUserProvisioningService users,
        AssetStore assets,
        MissionProgressService missionService,
        OperationIdempotencyService idempotency
    ) {
        this.queryService = queryService;
        this.users = users;
        this.assets = assets;
        this.missionService = missionService;
        this.idempotency = idempotency;
    }

    @GetMapping("/summary")
    public GamificationSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return queryService.summary(learnerId(jwt));
    }

    @GetMapping("/missions")
    public List<MissionView> missions(@AuthenticationPrincipal Jwt jwt) {
        return queryService.missions(learnerId(jwt));
    }

    @PostMapping("/missions/{missionId}/claim")
    public MissionProgressService.ClaimResult claimMission(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID missionId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (missionService == null) {
            throw new IllegalStateException("MissionProgressService is not configured");
        }
        UUID learner = learnerId(jwt);
        if (idempotency != null && idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotency.execute(
                learner,
                "GAMIFICATION_MISSION_CLAIM:" + missionId,
                idempotencyKey.trim(),
                missionId.toString(),
                MissionProgressService.ClaimResult.class,
                () -> missionService.claim(learner, missionId)
            );
        }
        return missionService.claim(learner, missionId);
    }

    @GetMapping("/achievements")
    public List<AchievementView> achievements(@AuthenticationPrincipal Jwt jwt) {
        return queryService.achievements(learnerId(jwt));
    }

    @GetMapping("/achievements/{id}/badge")
    public ResponseEntity<byte[]> achievementBadge(
        @PathVariable UUID id,
        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        AchievementDefinition achievement = queryService.findAchievement(id)
            .orElseThrow(() -> new ResourceNotFoundException("Achievement not found"));
        if (achievement.badgeAssetId() == null || assets == null) {
            throw new ResourceNotFoundException("Achievement badge not found");
        }
        AssetStore.Asset meta = assets.get(achievement.badgeAssetId());
        String eTag = "\"" + achievement.badgeAssetId() + "-" + meta.sizeBytes() + "\"";
        if (ifNoneMatch != null && (ifNoneMatch.trim().equals("*") || ifNoneMatch.contains(eTag))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .eTag(eTag)
                .build();
        }
        byte[] bytes = assets.bytes(achievement.badgeAssetId());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(meta.mimeType()))
            .cacheControl(CacheControl.noCache().mustRevalidate())
            .eTag(eTag)
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes);
    }

    @GetMapping("/ledger")
    public LedgerResponse ledger(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        GamificationQueryService.LedgerPage result = queryService.ledger(learnerId(jwt), page, size);
        return new LedgerResponse(
            result.balance(),
            result.transactions().stream().map(LedgerItemResponse::from).toList(),
            result.total(),
            result.page(),
            result.size(),
            result.hasMore()
        );
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record LedgerResponse(
        int balance,
        List<LedgerItemResponse> items,
        long total,
        int page,
        int size,
        boolean hasMore
    ) {}

    public record LedgerItemResponse(
        UUID id,
        DiamondTransaction.Type type,
        int amount,
        String referenceType,
        UUID referenceId,
        String reason,
        String ruleVersion,
        Instant createdAt
    ) {
        static LedgerItemResponse from(DiamondTransaction tx) {
            return new LedgerItemResponse(
                tx.id(),
                tx.type(),
                tx.amount(),
                tx.referenceType(),
                tx.referenceId(),
                tx.reason(),
                tx.ruleVersion(),
                tx.createdAt()
            );
        }
    }
}
