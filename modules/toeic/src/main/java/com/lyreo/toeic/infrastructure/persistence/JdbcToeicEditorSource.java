package com.lyreo.toeic.infrastructure.persistence;

import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.toeic.application.port.ToeicEditorSource;
import com.lyreo.toeic.domain.ToeicDraft;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToeicEditorSource implements ToeicEditorSource {
    private final NamedParameterJdbcTemplate jdbc;
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    public JdbcToeicEditorSource(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    public List<ReviewTarget> reviewTargets() {
        return jdbc.query("""
            SELECT g.package_group_id,v.catalog_id,g.id,v.name,g.part,g.question_numbers[1] AS first_question
            FROM toeic_stimulus_group g JOIN toeic_test_version v ON v.id=g.test_version_id
            JOIN dataset_active_release a ON a.release_id=v.release_id
            WHERE a.domain='grammar-toeic' AND g.document_parse_status='UNSPLIT'
            ORDER BY v.year,v.test_number,g.part,g.order_index,g.id
            """,Map.of(),(rs,n)->new ReviewTarget(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getInt(5),rs.getInt(6)));
    }
    public Optional<ToeicDraft> find(UUID id) {
        return jdbc.query("""
            SELECT v.*,c.access_mode,c.required_feature_key FROM toeic_test_version v
            JOIN toeic_test_catalog c ON c.id=v.catalog_id JOIN dataset_active_release a ON a.release_id=v.release_id
            WHERE a.domain='grammar-toeic' AND c.id=:id
            """,Map.of("id",id),(rs,n)->{
            UUID version=rs.getObject("id",UUID.class);
            var groups=jdbc.query("SELECT * FROM toeic_stimulus_group WHERE test_version_id=:version ORDER BY part,order_index,id",Map.of("version",version),(g,i)->{
                UUID group=g.getObject("id",UUID.class);
                var docs=jdbc.query("SELECT * FROM toeic_document WHERE group_id=:id ORDER BY ordinal",Map.of("id",group),(d,j)->new ToeicDraft.Document(d.getObject("id",UUID.class),d.getInt("ordinal"),d.getString("document_type"),d.getString("html")));
                if(docs.isEmpty() && g.getString("render_html")!=null) docs=List.of(new ToeicDraft.Document(group,1,"PASSAGE",g.getString("render_html")));
                var media=jdbc.query("SELECT a.id,u.role FROM media_asset_use u JOIN release_media_asset a ON a.release_id=u.release_id AND a.package_asset_id=u.asset_id WHERE u.group_id=:id",Map.of("id",group),(r,j)->new QuestionContent.Media(r.getObject(1,UUID.class),r.getString(2)));
                return new ToeicDraft.Group(group,g.getInt("part"),g.getString("kind"),g.getString("title"),g.getString("transcript_en"),g.getString("content_translation_vi"),g.getString("vocabulary_note_vi"),docs,media,!"UNSPLIT".equals(g.getString("document_parse_status")),JSON.readValue(g.getString("source_issues"),new tools.jackson.core.type.TypeReference<List<ToeicDraft.SourceIssue>>(){}));
            });
            var placements=jdbc.query("SELECT * FROM toeic_placement WHERE test_version_id=:version ORDER BY question_number",Map.of("version",version),
                (p,i)->new ToeicDraft.Placement(p.getObject("id",UUID.class),p.getObject("item_id",UUID.class),null,p.getObject("group_id",UUID.class),p.getInt("part"),p.getInt("question_number"),p.getObject("gap_number",Integer.class)));
            return new ToeicDraft(rs.getString("name"),"SOURCE-"+rs.getInt("year")+"-"+rs.getInt("test_number"),"FULL_TEST",rs.getInt("year"),rs.getInt("test_number"),rs.getObject("listening_duration_seconds",Integer.class),rs.getObject("reading_duration_seconds",Integer.class),rs.getString("access_mode"),rs.getString("required_feature_key"),groups,placements);
        }).stream().findFirst();
    }
    public Optional<Metadata> metadata(UUID id) {
        return jdbc.query("""
            SELECT v.name,v.year,v.test_number,v.listening_duration_seconds,v.reading_duration_seconds,v.total_questions,c.access_mode,c.required_feature_key,
              EXISTS(SELECT 1 FROM toeic_stimulus_group g
                WHERE g.test_version_id=v.id AND jsonb_array_length(g.source_issues)>0) AS source_blocked
            FROM toeic_test_version v JOIN toeic_test_catalog c ON c.id=v.catalog_id
            JOIN dataset_active_release a ON a.release_id=v.release_id WHERE a.domain='grammar-toeic' AND c.id=:id
            """,Map.of("id",id),(rs,n)->new Metadata(new ToeicDraft(rs.getString("name"),"SOURCE-"+rs.getInt("year")+"-"+rs.getInt("test_number"),"FULL_TEST",rs.getInt("year"),rs.getInt("test_number"),rs.getObject("listening_duration_seconds",Integer.class),rs.getObject("reading_duration_seconds",Integer.class),rs.getString("access_mode"),rs.getString("required_feature_key"),List.of(),List.of()),rs.getInt("total_questions"),rs.getBoolean("source_blocked"))).stream().findFirst();
    }
    public String status(UUID id) {return jdbc.queryForObject("SELECT publication_status FROM toeic_test_catalog WHERE id=:id",Map.of("id",id),String.class);}
    public List<Summary> list(String query,Integer year,String status,int page,int size) {return list(query,year,status,"","","",page,size);}
    public List<Summary> list(String query,Integer year,String status,String type,String access,String quality,int page,int size) {
        return jdbc.query(catalogQuery(false),parameters(query,year,status,type,access,quality,page,size),
            (rs,n)->new Summary(rs.getObject("id",UUID.class),rs.getString("title"),rs.getObject("year",Integer.class),rs.getString("type"),rs.getString("status"),rs.getString("access_mode"),rs.getInt("question_count"),rs.getString("quality_status")));
    }
    public long count(String query,Integer year,String status,String type,String access,String quality) {
        return jdbc.queryForObject(catalogQuery(true),parameters(query,year,status,type,access,quality,0,1),Long.class);
    }

    @Override
    public List<PublishedSummary> listPublished(int page, int size) {
        return jdbc.query(
            publishedQuery(false),
            Map.of("size", size, "offset", page * size),
            (rs, n) -> new PublishedSummary(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("type"),
                rs.getObject("year", Integer.class),
                rs.getInt("question_count"),
                rs.getObject("listening_duration_seconds", Integer.class),
                rs.getObject("reading_duration_seconds", Integer.class),
                rs.getString("access_mode"),
                rs.getString("required_feature_key")
            )
        );
    }

    @Override
    public long countPublished() {
        Long count = jdbc.queryForObject(publishedQuery(true), Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    private String publishedQuery(boolean count) {
        String selectClause = count ? "count(*)" : "*";
        String orderClause = count ? "" : "ORDER BY year DESC NULLS LAST, test_number NULLS LAST, title, id LIMIT :size OFFSET :offset";
        return """
            WITH tests AS (
                SELECT c.id,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'title' ELSE v.name END AS title,
                    CASE WHEN p.id IS NOT NULL THEN COALESCE(p.content->>'type', 'FULL_TEST') ELSE 'FULL_TEST' END AS type,
                    CASE WHEN p.id IS NOT NULL THEN CAST(p.content->>'year' AS integer) ELSE v.year END AS year,
                    CASE WHEN p.id IS NOT NULL THEN jsonb_array_length(p.content->'placements') ELSE v.total_questions END AS question_count,
                    CASE WHEN p.id IS NOT NULL THEN CAST(p.content->>'listeningDurationSeconds' AS integer) ELSE v.listening_duration_seconds END AS listening_duration_seconds,
                    CASE WHEN p.id IS NOT NULL THEN CAST(p.content->>'readingDurationSeconds' AS integer) ELSE v.reading_duration_seconds END AS reading_duration_seconds,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'accessMode' ELSE c.access_mode END AS access_mode,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'requiredFeatureKey' ELSE c.required_feature_key END AS required_feature_key,
                    CASE WHEN p.id IS NOT NULL THEN CAST(p.content->>'testNumber' AS integer) ELSE v.test_number END AS test_number
                FROM toeic_test_version v
                JOIN toeic_test_catalog c ON c.id = v.catalog_id
                JOIN dataset_active_release a ON a.release_id = v.release_id
                LEFT JOIN content_document d ON d.namespace = 'toeic' AND d.id = c.id
                    AND NOT EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace = d.namespace AND b.document_id = d.id AND NOT b.enabled)
                LEFT JOIN content_publication p ON p.id = d.publication_id AND d.status = 'PUBLISHED'
                WHERE a.domain = 'grammar-toeic'
                  AND (
                      (d.id IS NOT NULL AND d.status = 'PUBLISHED' AND p.id IS NOT NULL)
                      OR (
                          (d.id IS NULL OR (d.status = 'DRAFT' AND d.publication_id IS NULL))
                          AND c.publication_status = 'PUBLISHED'
                          AND NOT EXISTS(SELECT 1 FROM toeic_stimulus_group g WHERE g.test_version_id = v.id AND jsonb_array_length(g.source_issues) > 0)
                      )
                  )
                UNION ALL
                SELECT d.id,
                    p.content->>'title' AS title,
                    COALESCE(p.content->>'type', 'FULL_TEST') AS type,
                    CAST(p.content->>'year' AS integer) AS year,
                    jsonb_array_length(p.content->'placements') AS question_count,
                    CAST(p.content->>'listeningDurationSeconds' AS integer) AS listening_duration_seconds,
                    CAST(p.content->>'readingDurationSeconds' AS integer) AS reading_duration_seconds,
                    p.content->>'accessMode' AS access_mode,
                    p.content->>'requiredFeatureKey' AS required_feature_key,
                    CAST(p.content->>'testNumber' AS integer) AS test_number
                FROM content_document d
                JOIN content_publication p ON p.id = d.publication_id
                WHERE d.namespace = 'toeic'
                  AND d.status = 'PUBLISHED'
                  AND NOT EXISTS(SELECT 1 FROM toeic_test_version v JOIN dataset_active_release a ON a.release_id = v.release_id WHERE a.domain = 'grammar-toeic' AND v.catalog_id = d.id)
                  AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace = d.namespace AND off.document_id = d.id AND NOT off.enabled)
            )
            SELECT %s
            FROM tests
            WHERE type = 'FULL_TEST'
              AND question_count = 200
            %s
            """.formatted(selectClause, orderClause);
    }

    private String catalogQuery(boolean count) {

        return """
            WITH tests AS (
            SELECT c.id,COALESCE(d.draft->>'title',v.name) AS title,CASE WHEN d.id IS NULL THEN v.year ELSE CAST(d.draft->>'year' AS integer) END AS year,
                COALESCE(d.draft->>'type','FULL_TEST') AS type,COALESCE(NULLIF(d.status,'DRAFT'),c.publication_status) AS status,
                COALESCE(d.draft->>'accessMode',c.access_mode) AS access_mode,
                CASE WHEN d.id IS NULL THEN v.total_questions ELSE jsonb_array_length(d.draft->'placements') END AS question_count,
                COALESCE(CAST(d.draft->>'testNumber' AS integer),v.test_number) AS test_number
            FROM toeic_test_version v JOIN toeic_test_catalog c ON c.id=v.catalog_id
            JOIN dataset_active_release a ON a.release_id=v.release_id
            LEFT JOIN content_document d ON d.namespace='toeic' AND d.id=c.id AND NOT EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace=d.namespace AND b.document_id=d.id AND NOT b.enabled) WHERE a.domain='grammar-toeic'
            UNION ALL SELECT d.id,d.draft->>'title',CAST(d.draft->>'year' AS integer),d.draft->>'type',d.status,d.draft->>'accessMode',jsonb_array_length(d.draft->'placements'),CAST(d.draft->>'testNumber' AS integer)
            FROM content_document d WHERE d.namespace='toeic' AND NOT EXISTS(SELECT 1 FROM toeic_test_version v JOIN dataset_active_release a ON a.release_id=v.release_id WHERE a.domain='grammar-toeic' AND v.catalog_id=d.id) AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace=d.namespace AND off.document_id=d.id AND NOT off.enabled)
            ), classified AS (
            SELECT tests.*,COALESCE((SELECT r.status FROM content_quality_current r
                WHERE r.namespace='toeic' AND r.target_id=tests.id),'UNCHECKED') AS quality_status FROM tests
            ) SELECT %s FROM classified WHERE type='FULL_TEST' AND (:q='' OR title ILIKE :pattern)
                AND (CAST(:year AS integer) IS NULL OR year=:year) AND (:status='' OR status=:status) AND (:type='' OR type=:type) AND (:access='' OR access_mode=:access) AND (:quality='' OR quality_status=:quality OR (:quality='REVIEW' AND quality_status IN ('BLOCKED','WARNING','STALE','FAILED','UNCHECKED')))
            %s
            """ .formatted(count ? "count(*)" : "*", count ? "" : "ORDER BY year DESC NULLS LAST,test_number NULLS LAST,title,id LIMIT :size OFFSET :offset");
    }
    private MapSqlParameterSource parameters(String query,Integer year,String status,String type,String access,String quality,int page,int size) {
        return new MapSqlParameterSource().addValue("type",type).addValue("access",access).addValue("quality",quality).addValue("q",query).addValue("pattern","%"+query+"%").addValue("year",year).addValue("status",status).addValue("size",size).addValue("offset",page*size);
    }

}
