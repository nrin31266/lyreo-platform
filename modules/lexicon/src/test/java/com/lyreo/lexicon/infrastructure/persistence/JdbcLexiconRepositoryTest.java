package com.lyreo.lexicon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lyreo.lexicon.domain.LexiconEntry;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcLexiconRepositoryTest {
    private static final UUID RELEASE_ID = UUID.fromString("e9aefec1-1e22-49d2-9281-bd367a1b36b3");

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void searchLoadsFiveCollectionsInBatchesAndKeepsHeaderOrder() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID firstId = UUID.fromString("23c2c95b-f212-4707-9c89-3d4fd4f53877");
        UUID secondId = UUID.fromString("a66c13d0-ff66-4f26-9faf-144728230657");
        ResultSet firstHeader = headerRow(firstId, "first");
        ResultSet secondHeader = headerRow(secondId, "second");

        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(firstHeader, 0), mapper.mapRow(secondHeader, 1));
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        doAnswer(invocation -> java.util.Map.of())
            .when(jdbc).query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class));

        JdbcLexiconRepository repository = new JdbcLexiconRepository(jdbc);
        List<LexiconEntry> results = repository.search("te%_!rm", 20);

        assertThat(results).extracting(LexiconEntry::id).containsExactly(firstId, secondId);
        assertThat(results).allSatisfy(entry -> {
            assertThat(entry.items()).isEmpty();
            assertThat(entry.forms()).isEmpty();
            assertThat(entry.senses()).isEmpty();
            assertThat(entry.pronunciations()).isEmpty();
            assertThat(entry.translations()).isEmpty();
        });

        ArgumentCaptor<String> childSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> childParameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        ArgumentCaptor<String> headerSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> headerParameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(headerSql.capture(), headerParameters.capture(), any(RowMapper.class));
        assertThat(headerSql.getValue()).contains("ESCAPE '!'");
        assertThat(headerParameters.getValue().getValue("prefix")).isEqualTo("te!%!_!!rm%");
        verify(jdbc, org.mockito.Mockito.times(5)).query(
            childSql.capture(), childParameters.capture(), any(ResultSetExtractor.class)
        );
        assertThat(childSql.getAllValues())
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_item"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_form"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_sense"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_pronunciation"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_translation"));
        assertThat(childParameters.getAllValues()).allSatisfy(parameters -> {
            assertThat(parameters.getValue("release")).isEqualTo(RELEASE_ID);
            assertThat(parameters.getValue("entries")).isEqualTo(List.of(firstId, secondId));
        });
    }

    private static ResultSet headerRow(UUID id, String form) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        org.mockito.Mockito.when(rs.getObject(eq("id"), eq(UUID.class))).thenReturn(id);
        org.mockito.Mockito.when(rs.getObject(eq("release_id"), eq(UUID.class))).thenReturn(RELEASE_ID);
        org.mockito.Mockito.when(rs.getObject(eq("headword_id"), eq(UUID.class))).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(rs.getString("display_form")).thenReturn(form);
        org.mockito.Mockito.when(rs.getString("lookup_form")).thenReturn(form);
        org.mockito.Mockito.when(rs.getString("entry_type")).thenReturn("WORD");
        org.mockito.Mockito.when(rs.getString("language")).thenReturn("en");
        org.mockito.Mockito.when(rs.getString("source_metadata")).thenReturn("{}");
        org.mockito.Mockito.when(rs.getString("license_text")).thenReturn("license");
        return rs;
    }
}
