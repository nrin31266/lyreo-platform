package com.lyreo.platform.config.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyreo.platform.config.application.RuntimeConfigRepository;
import com.lyreo.platform.config.application.RuntimeConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class RuntimeConfigConfiguration {

    @Bean
    RuntimeConfigRepository runtimeConfigRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcRuntimeConfigRepository(jdbc, mapper);
    }

    @Bean
    RuntimeConfigService runtimeConfigService(RuntimeConfigRepository repository, ObjectMapper mapper) {
        return new RuntimeConfigService(repository, mapper);
    }
}
