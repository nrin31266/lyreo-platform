package com.lyreo.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DatabaseSetupIT {
    @Test
    void seedsTheSelectedDatabaseAndPreservesEditsWhenRepeated() {
        try (var postgres = new PostgreSQLContainer("postgres:18.6-alpine")) {
            postgres.start();
            var datasource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var seed = Path.of("../../infra/postgres/seed/dev.sql");
            DatabaseSetup.apply(datasource, seed);
            var jdbc = new JdbcTemplate(datasource);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_category", Integer.class)).isEqualTo(24);
            jdbc.update("UPDATE lesson_category SET name='Edited category' WHERE id='20000000-0000-0000-0000-000000000001'");
            DatabaseSetup.apply(datasource, seed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_category", Integer.class)).isEqualTo(24);
            assertThat(jdbc.queryForObject("SELECT name FROM lesson_category WHERE id='20000000-0000-0000-0000-000000000001'", String.class)).isEqualTo("Edited category");
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("001", "002");
        }
    }
}
