package com.lyreo.gamification.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.gamification.application.GamificationQueryService;
import com.lyreo.gamification.domain.AchievementDefinition;
import com.lyreo.gamification.domain.AchievementView;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.GamificationSummary;
import com.lyreo.gamification.domain.MissionView;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.AssetStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gamification")
public class GamificationController {
    private final GamificationQueryService queryService;
    private final AppUserProvisioningService users;
    private final AssetStore assets;

    public GamificationController(
        GamificationQueryService queryService,
        AppUserProvisioningService users
    ) {
        this(queryService, users, (AssetStore) null);
    }

    @Autowired
    public GamificationController(
        GamificationQueryService queryService,
        AppUserProvisioningService users,
        ObjectProvider<AssetStore> assetsProvider
    ) {
        this(queryService, users, assetsProvider.getIfAvailable());
    }

    public GamificationController(
        GamificationQueryService queryService,
        AppUserProvisioningService users,
        AssetStore assets
    ) {
        this.queryService = queryService;
        this.users = users;
        this.assets = assets;
    }

    @GetMapping("/summary")
    public GamificationSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return queryService.summary(learnerId(jwt));
    }

    @GetMapping("/missions")
    public List<MissionView> missions(@AuthenticationPrincipal Jwt jwt) {
        return queryService.missions(learnerId(jwt));
    }

    @GetMapping("/achievements")
    public List<AchievementView> achievements(@AuthenticationPrincipal Jwt jwt) {
        return queryService.achievements(learnerId(jwt));
    }

    @GetMapping("/achievements/{id}/badge")
    public ResponseEntity<byte[]> achievementBadge(@PathVariable UUID id) {
        AchievementDefinition achievement = queryService.findAchievement(id)
            .orElseThrow(() -> new ResourceNotFoundException("Achievement not found"));
        if (achievement.badgeAssetId() == null || assets == null) {
            throw new ResourceNotFoundException("Achievement badge not found");
        }
        AssetStore.Asset meta = assets.get(achievement.badgeAssetId());
        byte[] bytes = assets.bytes(achievement.badgeAssetId());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(meta.mimeType()))
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)))
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes);
    }

    @GetMapping("/ledger")
    public LedgerResponse ledger(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        GamificationQueryService.LedgerPage result = queryService.ledger(learnerId(jwt), page, size);
        return new LedgerResponse(
            result.balance(),
            result.page(),
            result.size(),
            result.transactions().stream().map(LedgerItemResponse::from).toList()
        );
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record LedgerResponse(
        int balance,
        int page,
        int size,
        List<LedgerItemResponse> transactions
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
