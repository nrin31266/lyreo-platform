package com.lyreo.analytics.infrastructure;

import com.lyreo.analytics.application.AnalyticsService;
import com.lyreo.analytics.application.port.AnalyticsProjectionRepository;
import com.lyreo.analytics.infrastructure.persistence.JdbcAnalyticsProjectionRepository;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class AnalyticsConfiguration {

    @Bean
    AnalyticsProjectionRepository analyticsProjectionRepository(
        NamedParameterJdbcTemplate jdbc
    ) {
        return new JdbcAnalyticsProjectionRepository(jdbc);
    }

    @Bean
    AnalyticsService analyticsService(
        AnalyticsProjectionRepository repository,
        ObjectProvider<ConsumerReceiptStore> receiptStoreProvider,
        ObjectProvider<Clock> clockProvider,
        NamedParameterJdbcTemplate jdbc
    ) {
        ConsumerReceiptStore receiptStore = receiptStoreProvider.getIfAvailable(() ->
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
        return new AnalyticsService(
            repository,
            receiptStore,
            clockProvider.getIfAvailable(Clock::systemUTC)
        );
    }
}
