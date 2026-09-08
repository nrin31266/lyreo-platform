package com.lyreo.analytics.infrastructure;

import com.lyreo.analytics.application.AnalyticsProjectionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class AnalyticsConfiguration {

    @Bean
    AnalyticsProjectionRepository analyticsProjectionRepository(
        NamedParameterJdbcTemplate jdbc
    ) {
        return new JdbcAnalyticsProjectionRepository(jdbc);
    }
}
