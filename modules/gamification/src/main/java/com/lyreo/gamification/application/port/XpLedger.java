package com.lyreo.gamification.application.port;

import com.lyreo.gamification.domain.LearnerLevel;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import com.lyreo.gamification.domain.XpTransaction;
import java.util.UUID;

public interface XpLedger {
    default XpTransaction append(XpTransaction transaction, int xpPerLevel) {
        return append(
            transaction,
            new RewardPolicyConfig(
                RewardPolicyConfig.DEFAULT_RULE_VERSION,
                false,
                RewardPolicyConfig.DEFAULT_CUTOVER,
                xpPerLevel,
                0,
                RewardPolicyConfig.DEFAULT_MAX_LEVEL
            )
        );
    }

    XpTransaction append(XpTransaction transaction, RewardPolicyConfig policy);

    LearnerLevel findLevel(UUID learnerId);
}
