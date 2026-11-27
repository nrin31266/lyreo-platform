package com.lyreo.gamification.infrastructure;

import com.lyreo.gamification.application.AchievementService;
import com.lyreo.gamification.application.GamificationAdminService;
import com.lyreo.gamification.application.GamificationQueryService;
import com.lyreo.gamification.application.MissionProgressService;
import com.lyreo.gamification.application.RewardPolicy;
import com.lyreo.gamification.application.StreakService;
import com.lyreo.gamification.application.port.AchievementRepository;
import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.application.port.MissionProgressRepository;
import com.lyreo.gamification.application.port.RewardPolicyConfigRepository;
import com.lyreo.gamification.application.port.StreakRepository;
import com.lyreo.gamification.application.port.XpLedger;
import com.lyreo.gamification.infrastructure.persistence.JdbcAchievementRepository;
import com.lyreo.gamification.infrastructure.persistence.JdbcDiamondLedger;
import com.lyreo.gamification.infrastructure.persistence.JdbcMissionProgressRepository;
import com.lyreo.gamification.infrastructure.persistence.JdbcStreakRepository;
import com.lyreo.gamification.infrastructure.persistence.JdbcXpLedger;
import com.lyreo.gamification.infrastructure.persistence.RuntimeRewardPolicyConfigRepository;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import com.lyreo.platform.config.application.RuntimeConfigService;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class GamificationConfiguration {
    @Bean
    DiamondLedger diamondLedger(NamedParameterJdbcTemplate jdbc) {
        return new JdbcDiamondLedger(jdbc);
    }

    @Bean
    XpLedger xpLedger(NamedParameterJdbcTemplate jdbc, DiamondLedger diamondLedger) {
        return new JdbcXpLedger(jdbc, diamondLedger);
    }

    @Bean
    MissionProgressRepository missionProgressRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcMissionProgressRepository(jdbc);
    }

    @Bean
    StreakRepository streakRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcStreakRepository(jdbc);
    }

    @Bean
    AchievementRepository achievementRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAchievementRepository(jdbc);
    }

    @Bean
    RewardPolicy rewardPolicy() {
        return new RewardPolicy();
    }

    @Bean
    RewardPolicyConfigRepository rewardPolicyConfigRepository(
        ObjectProvider<RuntimeConfigService> runtimeConfigProvider,
        NamedParameterJdbcTemplate jdbc,
        ObjectProvider<ObjectMapper> mapperProvider
    ) {
        ObjectMapper mapper = mapperProvider.getIfAvailable(ObjectMapper::new);
        RuntimeConfigService runtimeConfig = runtimeConfigProvider.getIfAvailable();
        if (runtimeConfig != null) {
            return new RuntimeRewardPolicyConfigRepository(runtimeConfig, mapper);
        }
        return new RuntimeRewardPolicyConfigRepository(jdbc, mapper);
    }

    @Bean
    MissionProgressService missionProgressService(
        MissionProgressRepository missions,
        DiamondLedger ledger,
        XpLedger xpLedger,
        ObjectProvider<ConsumerReceiptStore> receiptStoreProvider,
        RewardPolicyConfigRepository policyConfig,
        NamedParameterJdbcTemplate jdbc,
        ObjectProvider<ObjectMapper> mapperProvider
    ) {
        return new MissionProgressService(
            missions,
            ledger,
            xpLedger,
            resolveReceiptStore(receiptStoreProvider, jdbc),
            policyConfig,
            mapperProvider.getIfAvailable(ObjectMapper::new)
        );
    }

    @Bean
    StreakService streakService(
        StreakRepository streaks,
        ObjectProvider<ConsumerReceiptStore> receiptStoreProvider,
        NamedParameterJdbcTemplate jdbc
    ) {
        return new StreakService(streaks, resolveReceiptStore(receiptStoreProvider, jdbc));
    }

    @Bean
    AchievementService achievementService(
        AchievementRepository achievements,
        XpLedger xpLedger,
        DiamondLedger diamondLedger,
        ObjectProvider<ConsumerReceiptStore> receiptStoreProvider,
        RewardPolicyConfigRepository policyConfig,
        NamedParameterJdbcTemplate jdbc,
        ObjectProvider<ObjectMapper> mapperProvider
    ) {
        return new AchievementService(
            achievements,
            xpLedger,
            diamondLedger,
            resolveReceiptStore(receiptStoreProvider, jdbc),
            policyConfig,
            mapperProvider.getIfAvailable(ObjectMapper::new)
        );
    }

    @Bean
    GamificationQueryService gamificationQueryService(
        DiamondLedger diamondLedger,
        XpLedger xpLedger,
        StreakRepository streakRepository,
        MissionProgressRepository missionRepository,
        AchievementRepository achievementRepository,
        RewardPolicyConfigRepository policyConfig
    ) {
        return new GamificationQueryService(
            diamondLedger,
            xpLedger,
            streakRepository,
            missionRepository,
            achievementRepository,
            policyConfig
        );
    }

    @Bean
    GamificationAdminService gamificationAdminService(
        DiamondLedger diamondLedger,
        RewardPolicyConfigRepository policyConfig,
        NamedParameterJdbcTemplate jdbc,
        MissionProgressRepository missionRepository,
        AchievementRepository achievementRepository,
        ObjectProvider<ObjectMapper> mapperProvider
    ) {
        return new GamificationAdminService(
            diamondLedger,
            policyConfig,
            learnerId -> {
                Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM app_user WHERE id = :learnerId",
                    Map.of("learnerId", learnerId),
                    Integer.class
                );
                return count != null && count > 0;
            },
            missionRepository,
            achievementRepository,
            jdbc,
            mapperProvider.getIfAvailable(ObjectMapper::new)
        );
    }

    private static ConsumerReceiptStore resolveReceiptStore(
        ObjectProvider<ConsumerReceiptStore> receiptStoreProvider,
        NamedParameterJdbcTemplate jdbc
    ) {
        return receiptStoreProvider.getIfAvailable(() ->
            (consumer, factId) -> jdbc.update(
                """
                INSERT INTO consumer_event_receipt(consumer, fact_id)
                VALUES (:consumer, :factId)
                ON CONFLICT (consumer, fact_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                    .addValue("consumer", consumer)
                    .addValue("factId", factId)
            ) == 1
        );
    }
}
