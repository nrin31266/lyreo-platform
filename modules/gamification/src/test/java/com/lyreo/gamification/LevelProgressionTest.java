package com.lyreo.gamification;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.gamification.domain.LevelProgression;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import org.junit.jupiter.api.Test;

class LevelProgressionTest {

    @Test
    void escalatesXpWithMultiplierAndCapsAtLevel99WhileXpContinuesGrowing() {
        RewardPolicyConfig config = new RewardPolicyConfig(
            "v1", false, RewardPolicyConfig.DEFAULT_CUTOVER, 100, 25, 99, 1.03, 5, 15, 10
        );

        var start = LevelProgression.evaluate(0, config);
        assertThat(start.level()).isEqualTo(1);
        assertThat(start.xpForCurrentStep()).isEqualTo(100);
        assertThat(start.xpIntoCurrentLevel()).isEqualTo(0);
        assertThat(start.xpToNextLevel()).isEqualTo(100);
        assertThat(start.maxLevelReached()).isFalse();

        // Lv1 -> Lv2 requires 100 XP, Lv2 -> Lv3 requires round(125 * 1.03) = 129 XP
        var lvl2 = LevelProgression.evaluate(100, config);
        assertThat(lvl2.level()).isEqualTo(2);
        assertThat(lvl2.xpForCurrentStep()).isEqualTo(129);
        assertThat(lvl2.xpIntoCurrentLevel()).isEqualTo(0);
        assertThat(lvl2.xpToNextLevel()).isEqualTo(129);

        var lvl3 = LevelProgression.evaluate(229, config);
        assertThat(lvl3.level()).isEqualTo(3);
        assertThat(lvl3.xpForCurrentStep()).isGreaterThan(150);

        long xpFor99 = LevelProgression.totalXpForLevel(99, 100, 25, 1.03);
        var justBeforeMax = LevelProgression.evaluate(xpFor99 - 1, config);
        assertThat(justBeforeMax.level()).isEqualTo(98);
        assertThat(justBeforeMax.maxLevelReached()).isFalse();

        var capped = LevelProgression.evaluate(xpFor99 + 500_000L, config);
        assertThat(capped.level()).isEqualTo(99);
        assertThat(capped.maxLevel()).isEqualTo(99);
        assertThat(capped.maxLevelReached()).isTrue();
        assertThat(capped.xpIntoCurrentLevel()).isEqualTo(0);
        assertThat(capped.xpToNextLevel()).isEqualTo(0);
    }

    @Test
    void awardsProgressiveMilestoneDiamondsAtIntervalsAndCap() {
        RewardPolicyConfig config = RewardPolicyConfig.defaults();
        assertThat(LevelProgression.milestoneRewardDiamonds(4, config)).isZero();
        assertThat(LevelProgression.milestoneRewardDiamonds(5, config)).isEqualTo(15);
        assertThat(LevelProgression.milestoneRewardDiamonds(10, config)).isEqualTo(25);
        assertThat(LevelProgression.milestoneRewardDiamonds(15, config)).isEqualTo(35);
        assertThat(LevelProgression.milestoneRewardDiamonds(99, config)).isGreaterThan(100);

        var crossed = LevelProgression.crossedMilestones(3, 10, config);
        assertThat(crossed).containsExactly(
            new LevelProgression.MilestoneAward(5, 15),
            new LevelProgression.MilestoneAward(10, 25)
        );
    }

    @Test
    void enforcesHardCapAt99EvenWhenConfiguredAbove99() {
        var capped = LevelProgression.evaluate(10_000_000L, 100, 0, 1.0, 150);
        assertThat(capped.level()).isEqualTo(99);
        assertThat(capped.maxLevel()).isEqualTo(99);
        assertThat(capped.maxLevelReached()).isTrue();
    }
}
