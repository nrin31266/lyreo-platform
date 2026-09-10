package com.lyreo.gamification;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.gamification.application.RewardPolicy;
import org.junit.jupiter.api.Test;

class RewardPolicyTest {

    private final RewardPolicy policy = new RewardPolicy();

    @Test
    void rewardRequiresServerScoreAndFirstCompletion() {
        assertThat(policy.lessonActivityDiamonds("SHADOWING", 85, true)).isEqualTo(2);
        assertThat(policy.lessonActivityDiamonds("SHADOWING", 59, true)).isZero();
        assertThat(policy.lessonActivityDiamonds("SHADOWING", 100, false)).isZero();
    }

    @Test
    void practiceTypesHaveExplicitRewardAmounts() {
        assertThat(policy.lessonActivityDiamonds("DICTATION", 90, true)).isEqualTo(2);
        assertThat(policy.lessonActivityDiamonds("GRAMMAR_PRACTICE", 90, true)).isEqualTo(1);
        assertThat(policy.lessonActivityDiamonds("VOCABULARY_PRACTICE", 90, true)).isEqualTo(1);
        assertThat(policy.lessonActivityDiamonds("UNKNOWN", 90, true)).isZero();
    }
}
