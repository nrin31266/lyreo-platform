package com.lyreo.curriculum.infrastructure;

import com.lyreo.curriculum.application.CurriculumProgressRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class CurriculumConfiguration {

    @Bean
    CurriculumProgressRepository curriculumProgressRepository(
        NamedParameterJdbcTemplate jdbc
    ) {
        return new JdbcCurriculumProgressRepository(jdbc);
    }
}
