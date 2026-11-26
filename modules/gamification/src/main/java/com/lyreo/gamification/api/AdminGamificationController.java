package com.lyreo.gamification.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.gamification.application.GamificationAdminService;
import com.lyreo.gamification.application.GamificationQueryService;
import com.lyreo.gamification.domain.AchievementDefinition;
import com.lyreo.gamification.domain.AdminMissionDefinition;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.GamificationSummary;
import com.lyreo.gamification.domain.MissionDefinition;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/gamification")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGamificationController {
    private final GamificationAdminService adminService;
    private final GamificationQueryService queryService;

    public AdminGamificationController(GamificationAdminService adminService) {
        this(adminService, null);
    }

    @Autowired
    public AdminGamificationController(
        GamificationAdminService adminService,
        GamificationQueryService queryService
    ) {
        this.adminService = adminService;
        this.queryService = queryService;
    }

    @GetMapping("/learners/{learnerId}")
    public LearnerGamificationResponse learnerSummary(@PathVariable UUID learnerId) {
        if (!adminService.learnerExists(learnerId)) {
            throw new ResourceNotFoundException("Learner not found");
        }
        GamificationSummary summary = queryService.summary(learnerId);
        List<DiamondTransactionEntry> transactions = queryService.ledger(learnerId, 0, 20)
            .transactions()
            .stream()
            .map(DiamondTransactionEntry::from)
            .toList();
        return new LearnerGamificationResponse(
            summary.learnerId(),
            summary.xp(),
            summary.level(),
            summary.xpIntoCurrentLevel(),
            summary.xpPerLevel(),
            summary.diamondBalance(),
            summary.currentStreak(),
            summary.longestStreak(),
            summary.lastStudyDate(),
            transactions
        );
    }

    @PostMapping("/learners/{learnerId}/diamonds")
    public AdjustDiamondsResponse adjustDiamonds(
        @PathVariable UUID learnerId,
        @Valid @RequestBody AdjustDiamondsRequest request
    ) {
        GamificationAdminService.AdjustmentResult result = adminService.adjustDiamonds(
            learnerId,
            request.amount(),
            request.reason(),
            request.idempotencyKey()
        );
        return new AdjustDiamondsResponse(
            result.transactionId(),
            result.learnerId(),
            result.amount(),
            result.balance(),
            result.reason(),
            result.idempotencyKey(),
            result.ruleVersion(),
            result.createdAt()
        );
    }

    public record AdjustDiamondsRequest(
        @NotNull(message = "amount is required")
        Integer amount,
        @NotBlank(message = "reason is required")
        String reason,
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
    ) {}

    public record AdjustDiamondsResponse(
        UUID transactionId,
        UUID learnerId,
        int amount,
        int balance,
        String reason,
        String idempotencyKey,
        String ruleVersion,
        Instant createdAt
    ) {}

    public record DiamondTransactionEntry(
        UUID id,
        int amount,
        String reason,
        String sourceType,
        UUID sourceReference,
        Instant createdAt
    ) {
        static DiamondTransactionEntry from(DiamondTransaction tx) {
            return new DiamondTransactionEntry(
                tx.id(),
                tx.amount(),
                tx.reason() != null ? tx.reason() : tx.type().name(),
                tx.referenceType() != null ? tx.referenceType() : tx.type().name(),
                tx.referenceId(),
                tx.createdAt()
            );
        }
    }

    public record LearnerGamificationResponse(
        UUID learnerId,
        long xp,
        int level,
        long xpIntoLevel,
        int xpForNextLevel,
        int diamondBalance,
        int currentStreak,
        int longestStreak,
        LocalDate lastActiveDate,
        List<DiamondTransactionEntry> transactions
    ) {}

    @GetMapping("/missions")
    public List<AdminMissionDefinition> listMissions() {
        return adminService.listMissions();
    }

    @PostMapping("/missions")
    public ResponseEntity<AdminMissionDefinition> createMission(@Valid @RequestBody SaveMissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createMission(
            request.code(),
            request.title(),
            request.metric(),
            request.target(),
            request.xpReward(),
            request.diamondReward(),
            request.recurrence(),
            request.ruleVersion(),
            request.enabled(),
            request.category(),
            request.difficulty()
        ));
    }

    @PutMapping("/missions/{id}")
    public AdminMissionDefinition updateMission(
        @PathVariable UUID id,
        @Valid @RequestBody SaveMissionRequest request
    ) {
        return adminService.updateMission(
            id,
            request.code(),
            request.title(),
            request.metric(),
            request.target(),
            request.xpReward(),
            request.diamondReward(),
            request.recurrence(),
            request.ruleVersion(),
            request.enabled(),
            request.category(),
            request.difficulty()
        );
    }

    @GetMapping("/achievements")
    public List<AchievementDefinition> listAchievements() {
        return adminService.listAchievements();
    }

    @PostMapping("/achievements")
    public ResponseEntity<AchievementDefinition> createAchievement(@Valid @RequestBody SaveAchievementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createAchievement(
            request.code(),
            request.title(),
            request.description(),
            request.metric(),
            request.target(),
            request.xpReward(),
            request.diamondReward(),
            request.ruleVersion(),
            request.enabled(),
            request.badgeAssetId(),
            request.badgeTier(),
            request.category()
        ));
    }

    @PutMapping("/achievements/{id}")
    public AchievementDefinition updateAchievement(
        @PathVariable UUID id,
        @Valid @RequestBody SaveAchievementRequest request
    ) {
        return adminService.updateAchievement(
            id,
            request.code(),
            request.title(),
            request.description(),
            request.metric(),
            request.target(),
            request.xpReward(),
            request.diamondReward(),
            request.ruleVersion(),
            request.enabled(),
            request.badgeAssetId(),
            request.badgeTier(),
            request.category()
        );
    }

    @GetMapping("/reward-policy")
    public RewardPolicyConfig rewardPolicy() {
        return adminService.getRewardPolicy();
    }

    @PutMapping("/reward-policy")
    public RewardPolicyConfig updateRewardPolicy(@Valid @RequestBody SaveRewardPolicyRequest request) {
        return adminService.updateRewardPolicy(
            request.ruleVersion(),
            request.directLessonActivityDiamondsEnabled(),
            request.cutoverEffectiveAt(),
            request.xpPerLevel(),
            request.xpGrowthPerLevel() != null
                ? request.xpGrowthPerLevel()
                : RewardPolicyConfig.DEFAULT_XP_GROWTH_PER_LEVEL,
            request.maxLevel() != null
                ? request.maxLevel()
                : RewardPolicyConfig.DEFAULT_MAX_LEVEL,
            request.xpLevelMultiplier() != null
                ? request.xpLevelMultiplier()
                : RewardPolicyConfig.DEFAULT_XP_LEVEL_MULTIPLIER,
            request.milestoneInterval() != null
                ? request.milestoneInterval()
                : RewardPolicyConfig.DEFAULT_MILESTONE_INTERVAL,
            request.milestoneDiamondBase() != null
                ? request.milestoneDiamondBase()
                : RewardPolicyConfig.DEFAULT_MILESTONE_DIAMOND_BASE,
            request.milestoneDiamondGrowth() != null
                ? request.milestoneDiamondGrowth()
                : RewardPolicyConfig.DEFAULT_MILESTONE_DIAMOND_GROWTH
        );
    }

    public record SaveMissionRequest(
        @NotBlank String code,
        @NotBlank String title,
        @NotNull MissionDefinition.Metric metric,
        @Min(1) int target,
        @Min(0) int xpReward,
        @Min(0) int diamondReward,
        @NotNull MissionDefinition.Recurrence recurrence,
        String ruleVersion,
        boolean enabled,
        String category,
        String difficulty
    ) {
        public SaveMissionRequest(
            String code,
            String title,
            MissionDefinition.Metric metric,
            int target,
            int xpReward,
            int diamondReward,
            MissionDefinition.Recurrence recurrence,
            String ruleVersion,
            boolean enabled
        ) {
            this(code, title, metric, target, xpReward, diamondReward, recurrence, ruleVersion, enabled, "GENERAL", "NORMAL");
        }
    }

    public record SaveAchievementRequest(
        @NotBlank String code,
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String metric,
        @Min(1) int target,
        @Min(0) int xpReward,
        @Min(0) int diamondReward,
        String ruleVersion,
        boolean enabled,
        UUID badgeAssetId,
        String badgeTier,
        String category
    ) {
        public SaveAchievementRequest(
            String code,
            String title,
            String description,
            String metric,
            int target,
            int xpReward,
            int diamondReward,
            String ruleVersion,
            boolean enabled
        ) {
            this(code, title, description, metric, target, xpReward, diamondReward, ruleVersion, enabled, null, "BRONZE", "MILESTONE");
        }
    }

    public record SaveRewardPolicyRequest(
        @NotBlank String ruleVersion,
        boolean directLessonActivityDiamondsEnabled,
        Instant cutoverEffectiveAt,
        @Min(1) int xpPerLevel,
        @Min(0) Integer xpGrowthPerLevel,
        @Min(1) @Max(99) Integer maxLevel,
        Double xpLevelMultiplier,
        @Min(0) @Max(50) Integer milestoneInterval,
        @Min(0) Integer milestoneDiamondBase,
        @Min(0) Integer milestoneDiamondGrowth
    ) {}
}
