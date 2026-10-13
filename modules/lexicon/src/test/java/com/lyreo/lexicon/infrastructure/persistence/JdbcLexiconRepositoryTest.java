package com.lyreo.lexicon.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lyreo.lexicon.domain.LexiconEntry;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcLexiconRepositoryTest {
    private static final UUID RELEASE_ID = UUID.fromString("e9aefec1-1e22-49d2-9281-bd367a1b36b3");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"noun", "verb"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void detailPreservesSourcePosWithoutInferringItFromTarget(String sourcePos) throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID entryId = UUID.randomUUID();
        ResultSet header = headerRow(entryId, "bank");
        ResultSet translation = mock(ResultSet.class);
        org.mockito.Mockito.when(translation.next()).thenReturn(true, false);
        org.mockito.Mockito.when(translation.getObject("entry_id", UUID.class)).thenReturn(entryId);
        org.mockito.Mockito.when(translation.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(translation.getString("source_pos")).thenReturn(sourcePos);
        org.mockito.Mockito.when(translation.getString("link_status")).thenReturn("ENTRY_CANDIDATE");
        doAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(2)).mapRow(header, 0)))
            .when(jdbc).query(anyString(), any(java.util.Map.class), any(RowMapper.class));
        doAnswer(invocation -> java.util.Map.of())
            .when(jdbc).query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class));
        doAnswer(invocation -> ((ResultSetExtractor) invocation.getArgument(2)).extractData(translation))
            .when(jdbc).query(contains("FROM lexicon_translation"), any(SqlParameterSource.class),
                any(ResultSetExtractor.class));

        var found = new JdbcLexiconRepository(jdbc).findById(entryId).orElseThrow().translations().getFirst();
        assertThat(found.sourcePos()).isEqualTo(sourcePos);
        assertThat(found.itemId()).isNull();
        assertThat(found.senseId()).isNull();
        verify(translation, org.mockito.Mockito.never()).getString("source_context");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void detailLoadsFiveCollectionsInBatches() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID firstId = UUID.fromString("23c2c95b-f212-4707-9c89-3d4fd4f53877");
        ResultSet firstHeader = headerRow(firstId, "first");

        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(firstHeader, 0));
        }).when(jdbc).query(anyString(), any(java.util.Map.class), any(RowMapper.class));
        doAnswer(invocation -> java.util.Map.of())
            .when(jdbc).query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class));

        JdbcLexiconRepository repository = new JdbcLexiconRepository(jdbc);
        LexiconEntry result = repository.findById(firstId).orElseThrow();

        assertThat(result.id()).isEqualTo(firstId);
        assertThat(result.items()).isEmpty();
        assertThat(result.forms()).isEmpty();
        assertThat(result.senses()).isEmpty();
        assertThat(result.pronunciations()).isEmpty();
        assertThat(result.translations()).isEmpty();

        ArgumentCaptor<String> childSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> childParameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(5)).query(
            childSql.capture(), childParameters.capture(), any(ResultSetExtractor.class)
        );
        assertThat(childSql.getAllValues())
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_item"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_form"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_sense"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_pronunciation"))
            .anySatisfy(sql -> assertThat(sql).contains("FROM lexicon_translation", "source_pos")
                .doesNotContain("source_context"));
        assertThat(childParameters.getAllValues()).allSatisfy(parameters -> {
            assertThat(parameters.getValue("release")).isEqualTo(RELEASE_ID);
            assertThat(parameters.getValue("entries")).isEqualTo(List.of(firstId));
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void searchEscapesPrefixAndKeepsReleaseBoundedWithoutHydratingDetails() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ResultSet active = mock(ResultSet.class);
        org.mockito.Mockito.when(active.getObject(eq("release_id"), eq(UUID.class))).thenReturn(RELEASE_ID);
        org.mockito.Mockito.when(active.getString("source_metadata")).thenReturn("{}");
        org.mockito.Mockito.when(active.getString("license_text")).thenReturn("license");
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(2);
            return List.of(mapper.mapRow(active, 0));
        }).when(jdbc).query(contains("FROM dataset_active_release ar"), any(java.util.Map.class),
            any(RowMapper.class));
        doAnswer(invocation -> List.of()).when(jdbc)
            .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        var result = new JdbcLexiconRepository(jdbc).search("te%_!rm", "te%_!rm", 20);

        assertThat(result.results()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.licenseText()).isEqualTo("license");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(6))
            .query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues()).filteredOn(value -> value.contains("LIKE :prefix"))
            .allSatisfy(value -> assertThat(value).contains("ESCAPE '!'").contains("LIMIT :batch"));
        assertThat(parameters.getAllValues()).allSatisfy(value -> {
            assertThat(value.getValue("release")).isEqualTo(RELEASE_ID);
            assertThat(value.getValue("prefix")).isEqualTo("te!%!_!!rm%");
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
