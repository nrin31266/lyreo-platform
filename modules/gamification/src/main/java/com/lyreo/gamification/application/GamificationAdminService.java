package com.lyreo.gamification.application;

import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.gamification.application.port.AchievementRepository;
import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.application.port.MissionProgressRepository;
import com.lyreo.gamification.application.port.RewardPolicyConfigRepository;
import com.lyreo.gamification.domain.AchievementDefinition;
import com.lyreo.gamification.domain.AdminMissionDefinition;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.MissionDefinition;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** Admin-initiated idempotent diamond ledger adjustments and mission/achievement/policy management. */
public class GamificationAdminService {
    private final DiamondLedger ledger;
    private final RewardPolicyConfigRepository policyConfig;
    private final Predicate<UUID> learnerExists;
    private final MissionProgressRepository missions;
    private final AchievementRepository achievements;

    public GamificationAdminService(
        DiamondLedger ledger,
        RewardPolicyConfigRepository policyConfig,
        Predicate<UUID> learnerExists
    ) {
        this(ledger, policyConfig, learnerExists, null, null);
    }

    public GamificationAdminService(
        DiamondLedger ledger,
        RewardPolicyConfigRepository policyConfig,
        Predicate<UUID> learnerExists,
        MissionProgressRepository missions,
        AchievementRepository achievements
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.policyConfig = Objects.requireNonNull(policyConfig, "policyConfig");
        this.learnerExists = Objects.requireNonNull(learnerExists, "learnerExists");
        this.missions = missions;
        this.achievements = achievements;
    }

    public List<AdminMissionDefinition> listMissions() {
        return missions != null ? missions.listAllMissions() : List.of();
    }

    @Transactional
    public AdminMissionDefinition createMission(
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
        return createMission(code, title, metric, target, xpReward, diamondReward, recurrence, ruleVersion, enabled, "GENERAL", "NORMAL");
    }

    @Transactional
    public AdminMissionDefinition createMission(
        String code,
        String title,
        MissionDefinition.Metric metric,
        int target,
        int xpReward,
        int diamondReward,
        MissionDefinition.Recurrence recurrence,
        String ruleVersion,
        boolean enabled,
        String category,
        String difficulty
    ) {
        if (missions == null) {
            throw new IllegalStateException("MissionProgressRepository is not configured");
        }
        AdminMissionDefinition toCreate = validateMission(
            UUID.randomUUID(),
            code,
            title,
            metric,
            target,
            xpReward,
            diamondReward,
            recurrence,
            ruleVersion,
            enabled,
            category,
            difficulty
        );
        try {
            return missions.createMission(toCreate);
        } catch (DataIntegrityViolationException ex) {
            throw new StateConflictException("Mission code already exists: " + toCreate.code(), ex);
        }
    }

    @Transactional
    public AdminMissionDefinition updateMission(
        UUID id,
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
        return updateMission(id, code, title, metric, target, xpReward, diamondReward, recurrence, ruleVersion, enabled, "GENERAL", "NORMAL");
    }

    @Transactional
    public AdminMissionDefinition updateMission(
        UUID id,
        String code,
        String title,
        MissionDefinition.Metric metric,
        int target,
        int xpReward,
        int diamondReward,
        MissionDefinition.Recurrence recurrence,
        String ruleVersion,
        boolean enabled,
        String category,
        String difficulty
    ) {
        if (missions == null) {
            throw new IllegalStateException("MissionProgressRepository is not configured");
        }
        Objects.requireNonNull(id, "id");
        AdminMissionDefinition updated = validateMission(
            id,
            code,
            title,
            metric,
            target,
            xpReward,
            diamondReward,
            recurrence,
            ruleVersion,
            enabled,
            category,
            difficulty
        );
        try {
            return missions.updateMission(updated)
                .orElseThrow(() -> new ResourceNotFoundException("Mission not found: " + id));
        } catch (DataIntegrityViolationException ex) {
            throw new StateConflictException("Mission code already exists: " + updated.code(), ex);
        }
    }

    public List<AchievementDefinition> listAchievements() {
        return achievements != null ? achievements.listAllAchievements() : List.of();
    }

    @Transactional
    public AchievementDefinition createAchievement(
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
        return createAchievement(code, title, description, metric, target, xpReward, diamondReward, ruleVersion, enabled, null, "BRONZE", "MILESTONE");
    }

    @Transactional
    public AchievementDefinition createAchievement(
        String code,
        String title,
        String description,
        String metric,
        int target,
        int xpReward,
        int diamondReward,
        String ruleVersion,
        boolean enabled,
        UUID badgeAssetId,
        String badgeTier,
        String category
    ) {
        if (achievements == null) {
            throw new IllegalStateException("AchievementRepository is not configured");
        }
        AchievementDefinition toCreate = validateAchievement(
            UUID.randomUUID(),
            code,
            title,
            description,
            metric,
            target,
            xpReward,
            diamondReward,
            ruleVersion,
            enabled,
            badgeAssetId,
            badgeTier,
            category
        );
        try {
            return achievements.createAchievement(toCreate);
        } catch (DataIntegrityViolationException ex) {
            throw new StateConflictException("Achievement code already exists: " + toCreate.code(), ex);
        }
    }

    @Transactional
    public AchievementDefinition updateAchievement(
        UUID id,
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
        return updateAchievement(id, code, title, description, metric, target, xpReward, diamondReward, ruleVersion, enabled, null, "BRONZE", "MILESTONE");
    }

    @Transactional
    public AchievementDefinition updateAchievement(
        UUID id,
        String code,
        String title,
        String description,
        String metric,
        int target,
        int xpReward,
        int diamondReward,
        String ruleVersion,
        boolean enabled,
        UUID badgeAssetId,
        String badgeTier,
        String category
    ) {
        if (achievements == null) {
            throw new IllegalStateException("AchievementRepository is not configured");
        }
        Objects.requireNonNull(id, "id");
        AchievementDefinition updated = validateAchievement(
            id,
            code,
            title,
            description,
            metric,
            target,
            xpReward,
            diamondReward,
            ruleVersion,
            enabled,
            badgeAssetId,
            badgeTier,
            category
        );
        try {
            return achievements.updateAchievement(updated)
                .orElseThrow(() -> new ResourceNotFoundException("Achievement not found: " + id));
        } catch (DataIntegrityViolationException ex) {
            throw new StateConflictException("Achievement code already exists: " + updated.code(), ex);
        }
    }

    public RewardPolicyConfig getRewardPolicy() {
        return policyConfig.current();
    }

    @Transactional
    public RewardPolicyConfig updateRewardPolicy(
        String ruleVersion,
        boolean directLessonActivityDiamondsEnabled,
        Instant cutoverEffectiveAt,
        int xpPerLevel
    ) {
        return updateRewardPolicy(
            ruleVersion,
            directLessonActivityDiamondsEnabled,
            cutoverEffectiveAt,
            xpPerLevel,
            RewardPolicyConfig.DEFAULT_XP_GROWTH_PER_LEVEL,
            RewardPolicyConfig.DEFAULT_MAX_LEVEL
        );
    }

    @Transactional
    public RewardPolicyConfig updateRewardPolicy(
        String ruleVersion,
        boolean directLessonActivityDiamondsEnabled,
        Instant cutoverEffectiveAt,
        int xpPerLevel,
        int xpGrowthPerLevel,
        int maxLevel
    ) {
        return updateRewardPolicy(
            ruleVersion,
            directLessonActivityDiamondsEnabled,
            cutoverEffectiveAt,
            xpPerLevel,
            xpGrowthPerLevel,
            maxLevel,
            RewardPolicyConfig.DEFAULT_XP_LEVEL_MULTIPLIER,
            RewardPolicyConfig.DEFAULT_MILESTONE_INTERVAL,
            RewardPolicyConfig.DEFAULT_MILESTONE_DIAMOND_BASE,
            RewardPolicyConfig.DEFAULT_MILESTONE_DIAMOND_GROWTH
        );
    }

    @Transactional
    public RewardPolicyConfig updateRewardPolicy(
        String ruleVersion,
        boolean directLessonActivityDiamondsEnabled,
        Instant cutoverEffectiveAt,
        int xpPerLevel,
        int xpGrowthPerLevel,
        int maxLevel,
        double xpLevelMultiplier,
        int milestoneInterval,
        int milestoneDiamondBase,
        int milestoneDiamondGrowth
    ) {
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new RequestValidationException("ruleVersion is required");
        }
        if (xpPerLevel <= 0 || xpPerLevel > 100_000) {
            throw new RequestValidationException("xpPerLevel must be between 1 and 100000");
        }
        if (xpGrowthPerLevel < 0 || xpGrowthPerLevel > 10_000) {
            throw new RequestValidationException("xpGrowthPerLevel must be between 0 and 10000");
        }
        if (maxLevel < 1 || maxLevel > RewardPolicyConfig.DEFAULT_MAX_LEVEL) {
            throw new RequestValidationException("maxLevel must be between 1 and 99");
        }
        if (!Double.isFinite(xpLevelMultiplier) || xpLevelMultiplier < 1.0 || xpLevelMultiplier > 1.25) {
            throw new RequestValidationException("xpLevelMultiplier must be between 1.00 and 1.25");
        }
        if (milestoneInterval < 0 || milestoneInterval > 50) {
            throw new RequestValidationException("milestoneInterval must be between 0 and 50");
        }
        if (milestoneDiamondBase < 0 || milestoneDiamondBase > 10_000 || milestoneDiamondGrowth < 0 || milestoneDiamondGrowth > 5_000) {
            throw new RequestValidationException("milestone diamond values out of range");
        }
        Instant cutover = cutoverEffectiveAt != null
            ? cutoverEffectiveAt
            : RewardPolicyConfig.DEFAULT_CUTOVER;
        return policyConfig.save(new RewardPolicyConfig(
            ruleVersion.trim(),
            directLessonActivityDiamondsEnabled,
            cutover,
            xpPerLevel,
            xpGrowthPerLevel,
            maxLevel,
            xpLevelMultiplier,
            milestoneInterval,
            milestoneDiamondBase,
            milestoneDiamondGrowth
        ));
    }

    private static AdminMissionDefinition validateMission(
        UUID id,
        String code,
        String title,
        MissionDefinition.Metric metric,
        int target,
        int xpReward,
        int diamondReward,
        MissionDefinition.Recurrence recurrence,
        String ruleVersion,
        boolean enabled,
        String category,
        String difficulty
    ) {
        if (code == null || code.isBlank() || code.trim().length() > 100) {
            throw new RequestValidationException("code is required and must be <= 100 characters");
        }
        if (title == null || title.isBlank()) {
            throw new RequestValidationException("title is required");
        }
        if (metric == null || recurrence == null) {
            throw new RequestValidationException("metric and recurrence are required");
        }
        if (target <= 0 || xpReward < 0 || diamondReward < 0) {
            throw new RequestValidationException("target must be > 0 and rewards must be >= 0");
        }
        String resolvedVersion = (ruleVersion == null || ruleVersion.isBlank())
            ? RewardPolicyConfig.DEFAULT_RULE_VERSION
            : ruleVersion.trim();
        String resolvedCategory = (category == null || category.isBlank()) ? "GENERAL" : category.trim().toUpperCase(java.util.Locale.ROOT);
        String resolvedDifficulty = (difficulty == null || difficulty.isBlank()) ? "NORMAL" : difficulty.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("EASY", "NORMAL", "HARD").contains(resolvedDifficulty)) {
            throw new RequestValidationException("difficulty must be EASY, NORMAL, or HARD");
        }
        return new AdminMissionDefinition(
            id,
            code.trim(),
            title.trim(),
            metric,
            target,
            xpReward,
            diamondReward,
            recurrence,
            resolvedVersion,
            enabled,
            resolvedCategory,
            resolvedDifficulty
        );
    }

    private static AchievementDefinition validateAchievement(
        UUID id,
        String code,
        String title,
        String description,
        String metric,
        int target,
        int xpReward,
        int diamondReward,
        String ruleVersion,
        boolean enabled,
        UUID badgeAssetId,
        String badgeTier,
        String category
    ) {
        if (code == null || code.isBlank() || code.trim().length() > 100) {
            throw new RequestValidationException("code is required and must be <= 100 characters");
        }
        if (title == null || title.isBlank() || description == null || description.isBlank()) {
            throw new RequestValidationException("title and description are required");
        }
        if (metric == null || metric.isBlank() || metric.trim().length() > 64) {
            throw new RequestValidationException("metric is required and must be <= 64 characters");
        }
        if (target <= 0 || xpReward < 0 || diamondReward < 0) {
            throw new RequestValidationException("target must be > 0 and rewards must be >= 0");
        }
        String resolvedVersion = (ruleVersion == null || ruleVersion.isBlank())
            ? RewardPolicyConfig.DEFAULT_RULE_VERSION
            : ruleVersion.trim();
        String resolvedTier = (badgeTier == null || badgeTier.isBlank())
            ? "BRONZE"
            : badgeTier.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("BRONZE", "SILVER", "GOLD", "PLATINUM", "LEGENDARY").contains(resolvedTier)) {
            throw new RequestValidationException("badgeTier must be BRONZE, SILVER, GOLD, PLATINUM, or LEGENDARY");
        }
        String resolvedCategory = (category == null || category.isBlank())
            ? "MILESTONE"
            : category.trim().toUpperCase(java.util.Locale.ROOT);
        return new AchievementDefinition(
            id,
            code.trim(),
            title.trim(),
            description.trim(),
            metric.trim(),
            target,
            xpReward,
            diamondReward,
            resolvedVersion,
            enabled,
            badgeAssetId,
            resolvedTier,
            resolvedCategory
        );
    }

    public boolean learnerExists(UUID learnerId) {
        return learnerId != null && learnerExists.test(learnerId);
    }

    @Transactional
    public AdjustmentResult adjustDiamonds(
        UUID learnerId,
        int amount,
        String reason,
        String idempotencyKey
    ) {
        if (learnerId == null || !learnerExists.test(learnerId)) {
            throw new ResourceNotFoundException("Learner not found");
        }
        if (amount == 0) {
            throw new RequestValidationException("amount must be non-zero");
        }
        if (reason == null || reason.isBlank()) {
            throw new RequestValidationException("reason is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RequestValidationException("idempotencyKey is required");
        }

        String normalizedReason = reason.trim();
        String normalizedKey = idempotencyKey.trim();
        String scopedKey = "admin:" + learnerId + ":" + normalizedKey;

        Optional<DiamondTransaction> existingOpt = ledger.findByIdempotencyKey(scopedKey);
        if (existingOpt.isPresent()) {
            DiamondTransaction existing = existingOpt.get();
            if (!existing.learnerId().equals(learnerId)
                || existing.amount() != amount
                || !Objects.equals(existing.reason(), normalizedReason)) {
                throw new IdempotencyConflictException(
                    "Idempotency key was already used with a different adjustment payload",
                    normalizedKey
                );
            }
            return new AdjustmentResult(
                existing.id(),
                learnerId,
                existing.amount(),
                ledger.balance(learnerId),
                existing.reason(),
                normalizedKey,
                existing.ruleVersion(),
                existing.createdAt()
            );
        }

        int currentBalance = ledger.balance(learnerId);
        if (currentBalance + amount < 0) {
            throw new StateConflictException("Insufficient diamond balance for negative adjustment");
        }

        String ruleVersion = policyConfig.current().normalizedRuleVersion();
        DiamondTransaction tx = ledger.append(new DiamondTransaction(
            UUID.randomUUID(),
            learnerId,
            DiamondTransaction.Type.ADMIN_ADJUSTMENT,
            amount,
            scopedKey,
            "ADMIN_ADJUSTMENT",
            null,
            Instant.now(),
            normalizedReason,
            ruleVersion
        ));

        return new AdjustmentResult(
            tx.id(),
            learnerId,
            tx.amount(),
            ledger.balance(learnerId),
            tx.reason(),
            normalizedKey,
            tx.ruleVersion(),
            tx.createdAt()
        );
    }

    public record AdjustmentResult(
        UUID transactionId,
        UUID learnerId,
        int amount,
        int balance,
        String reason,
        String idempotencyKey,
        String ruleVersion,
        Instant createdAt
    ) {}
}
