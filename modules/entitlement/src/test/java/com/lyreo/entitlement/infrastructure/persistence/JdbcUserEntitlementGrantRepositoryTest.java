package com.lyreo.entitlement.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lyreo.entitlement.api.FeatureKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcUserEntitlementGrantRepositoryTest {
    @Test
    void evaluatesGrantStatusRevocationAndHalfOpenValidityWindow() {
        NamedParameterJdbcTemplate jdbc = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
        var repository = new JdbcUserEntitlementGrantRepository(jdbc);
        UUID userId = UUID.fromString("a6f6db04-8fd4-4e92-9329-57f4860ee7a4");
        FeatureKey feature = FeatureKey.of("grammar.advanced");
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);

        assertThat(repository.hasActiveGrant(userId, feature, now)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(sql.capture(), parameters.capture(), eq(Boolean.class));
        assertThat(sql.getValue())
            .contains("g.status = 'ACTIVE'")
            .contains("g.revoked_at IS NULL")
            .contains("g.valid_from <= :asOf")
            .contains("g.valid_until > :asOf");
        assertThat(parameters.getValue().getValue("userId")).isEqualTo(userId);
        assertThat(parameters.getValue().getValue("featureKey")).isEqualTo("grammar.advanced");
        assertThat(((Timestamp) parameters.getValue().getValue("asOf")).toInstant()).isEqualTo(now);
    }
}
