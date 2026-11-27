package com.lyreo.gamification.application.port;

import com.lyreo.gamification.domain.RewardPolicyConfig;

public interface RewardPolicyConfigRepository {
    RewardPolicyConfig current();

    default RewardPolicyConfig save(RewardPolicyConfig config) {
        return config;
    }
}
