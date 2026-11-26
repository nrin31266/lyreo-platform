package com.lyreo.gamification.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.gamification.application.port.AchievementRepository;
import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.application.port.MissionProgressRepository;
import com.lyreo.gamification.application.port.RewardPolicyConfigRepository;
import com.lyreo.gamification.application.port.StreakRepository;
import com.lyreo.gamification.application.port.XpLedger;
import com.lyreo.gamification.domain.AchievementView;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.GamificationSummary;
import com.lyreo.gamification.domain.LearnerLevel;
import com.lyreo.gamification.domain.LearnerStreak;
import com.lyreo.gamification.domain.LevelProgression;
import com.lyreo.gamification.domain.MissionView;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Read-side queries for learner gamification summary, missions, achievements, and ledger. */
public class GamificationQueryService {
    private final DiamondLedger diamondLedger;
    private final XpLedger xpLedger;
    private final StreakRepository streakRepository;
    private final MissionProgressRepository missionRepository;
    private final AchievementRepository achievementRepository;
    private final RewardPolicyConfigRepository policyConfig;

    public GamificationQueryService(
        DiamondLedger diamondLedger,
        XpLedger xpLedger,
        StreakRepository streakRepository,
        MissionProgressRepository missionRepository,
        AchievementRepository achievementRepository,
        RewardPolicyConfigRepository policyConfig
    ) {
        this.diamondLedger = Objects.requireNonNull(diamondLedger, "diamondLedger");
        this.xpLedger = Objects.requireNonNull(xpLedger, "xpLedger");
        this.streakRepository = Objects.requireNonNull(streakRepository, "streakRepository");
        this.missionRepository = Objects.requireNonNull(missionRepository, "missionRepository");
        this.achievementRepository = Objects.requireNonNull(achievementRepository, "achievementRepository");
        this.policyConfig = Objects.requireNonNull(policyConfig, "policyConfig");
    }

    @Transactional(readOnly = true)
    public GamificationSummary summary(UUID learnerId) {
        RewardPolicyConfig config = policyConfig.current();
        LearnerLevel level = xpLedger.findLevel(learnerId);
        LevelProgression.Progress progress = LevelProgression.evaluate(level.xp(), config);
        int diamondBalance = diamondLedger.balance(learnerId);
        LearnerStreak streak = streakRepository.findStreak(learnerId);

        int nextMilestoneLevel = 0;
        int nextMilestoneDiamonds = 0;
        for (int lvl = progress.level() + 1; lvl <= progress.maxLevel(); lvl++) {
            int bonus = LevelProgression.milestoneRewardDiamonds(lvl, config);
            if (bonus > 0) {
                nextMilestoneLevel = lvl;
                nextMilestoneDiamonds = bonus;
                break;
            }
        }

        return new GamificationSummary(
            learnerId,
            progress.level(),
            level.xp(),
            progress.xpForCurrentStep(),
            progress.xpIntoCurrentLevel(),
            progress.xpToNextLevel(),
            diamondBalance,
            streak.currentStreak(),
            streak.longestStreak(),
            streak.lastStudyDate(),
            config.normalizedRuleVersion(),
            config.normalizedXpGrowthPerLevel(),
            config.normalizedXpLevelMultiplier(),
            progress.maxLevel(),
            progress.maxLevelReached(),
            config.normalizedMilestoneInterval(),
            nextMilestoneLevel,
            nextMilestoneDiamonds
        );
    }

    @Transactional(readOnly = true)
    public List<MissionView> missions(UUID learnerId) {
        Instant now = Instant.now();
        LocalDate utcToday = now.atZone(ZoneOffset.UTC).toLocalDate();
        String dailyKey = MissionProgressService.periodKey("DAILY", now);
        String weeklyKey = MissionProgressService.periodKey("WEEKLY", now);
        Instant dailyEndsAt = utcToday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant weeklyEndsAt = utcToday
            .with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
        return missionRepository.listActiveMissionsForLearner(
            learnerId,
            dailyKey,
            weeklyKey,
            dailyEndsAt,
            weeklyEndsAt
        );
    }

    @Transactional(readOnly = true)
    public List<AchievementView> achievements(UUID learnerId) {
        return achievementRepository.listLearnerAchievements(learnerId);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<com.lyreo.gamification.domain.AchievementDefinition> findAchievement(UUID id) {
        return achievementRepository.findAchievementById(id);
    }

    @Transactional(readOnly = true)
    public LedgerPage ledger(UUID learnerId, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new RequestValidationException("Invalid ledger page or size");
        }
        int balance = diamondLedger.balance(learnerId);
        List<DiamondTransaction> items = diamondLedger.listByLearner(learnerId, page, size);
        return new LedgerPage(balance, page, size, items);
    }

    public record LedgerPage(
        int balance,
        int page,
        int size,
        List<DiamondTransaction> transactions
    ) {}
}
