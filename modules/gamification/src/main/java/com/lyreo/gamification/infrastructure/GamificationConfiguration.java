package com.lyreo.gamification.infrastructure;

import com.lyreo.gamification.application.DiamondLedger;
import com.lyreo.gamification.application.MissionProgressRepository;
import com.lyreo.gamification.application.MissionProgressService;
import com.lyreo.gamification.application.RewardPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class GamificationConfiguration {
    @Bean
    DiamondLedger diamondLedger(NamedParameterJdbcTemplate jdbc) {
        return new JdbcDiamondLedger(jdbc);
    }

    @Bean
    MissionProgressRepository missionProgressRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcMissionProgressRepository(jdbc);
    }

    @Bean
    RewardPolicy rewardPolicy() {
        return new RewardPolicy();
    }

    @Bean
    MissionProgressService missionProgressService(
        MissionProgressRepository missions,
        DiamondLedger ledger
    ) {
        return new MissionProgressService(missions, ledger);
    }
}
