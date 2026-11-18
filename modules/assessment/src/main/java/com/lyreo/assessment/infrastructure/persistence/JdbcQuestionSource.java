package com.lyreo.assessment.infrastructure.persistence;
import com.lyreo.assessment.application.port.QuestionSource;
import com.lyreo.assessment.domain.QuestionContent;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.namedparam.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

@Repository
public class JdbcQuestionSource implements QuestionSource {
    private final NamedParameterJdbcTemplate jdbc; private final ObjectMapper json;
    public JdbcQuestionSource(NamedParameterJdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    public Optional<QuestionContent> current(UUID id) {
        var active=jdbc.query("""
            SELECT i.id FROM assessment_item i JOIN dataset_active_release a ON a.release_id=i.release_id
            JOIN assessment_item old ON old.package_item_id=i.package_item_id
            WHERE old.id=:id AND a.domain='grammar-toeic'
            """,Map.of("id",id),(rs,n)->rs.getObject(1,UUID.class));
        return active.isEmpty()?Optional.empty():find(active.getFirst());
    }
    public Optional<QuestionContent> find(UUID id) {
        return jdbc.query("""
            SELECT i.* FROM assessment_item i WHERE i.id=:id AND EXISTS (
                SELECT 1 FROM dataset_import_run r WHERE r.release_id=i.release_id
                AND r.run_type='APPLY' AND r.status='SUCCEEDED')
            """,Map.of("id",id),(rs,n)->{
                Map<String,Object> annotations=json.readValue(rs.getString("annotations"),new TypeReference<>(){});
                List<QuestionContent.Media> media=jdbc.query("""
                    SELECT a.id,u.role FROM media_asset_use u JOIN release_media_asset a
                    ON a.release_id=u.release_id AND a.package_asset_id=u.asset_id WHERE u.item_id=:id
                    """,Map.of("id",id),(r,i)->new QuestionContent.Media(r.getObject(1,UUID.class),r.getString(2)));
                return new QuestionContent(rs.getString("stem_en"),rs.getString("kind"),json.readValue(rs.getString("options"),new TypeReference<List<QuestionContent.Option>>(){}),rs.getString("correct_option"),rs.getObject("difficulty_level",Integer.class),
                    text(annotations,"rationale_vi"),text(annotations,"content_translation_vi"),text(annotations,"option_translation_note_vi"),text(annotations,"vocabulary_note_vi"),rs.getString("transcript_en"),media,text(annotations,"question_options_translation_vi"));
            }).stream().findFirst();
    }
    private static String text(Map<String,Object> values,String key) {return values.get(key) instanceof String s?s:null;}
    public boolean requiresContext(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM toeic_placement WHERE item_id=:id AND group_id IS NOT NULL)",Map.of("id",id),Boolean.class));
    }
    public List<Map<String,Object>> context(UUID id) {
        return jdbc.queryForList("""
            SELECT 'IMPORTED_REFERENCE' AS provenance,p.part,p.gap_number,g.transcript_en,
                COALESCE((SELECT string_agg(d.html,E'\\n' ORDER BY d.ordinal) FROM toeic_document d WHERE d.group_id=g.id),g.render_html) AS passage_html
            FROM toeic_placement p JOIN toeic_stimulus_group g ON g.id=p.group_id WHERE p.item_id=:id ORDER BY p.id LIMIT 5
            """,Map.of("id",id));
    }
    public List<Summary> list(String query,Integer difficulty,String status,int page,int size) {
        return jdbc.query(catalogQuery(false),parameters(query,difficulty,status,page,size),
            (rs,n)->new Summary(rs.getObject("id",UUID.class),rs.getString("title"),rs.getObject("difficulty",Integer.class),rs.getString("status"),rs.getBoolean("edited"),rs.getString("type"),rs.getBoolean("requires_context")));
    }
    public long count(String query,Integer difficulty,String status) {
        return jdbc.queryForObject(catalogQuery(true),parameters(query,difficulty,status,0,1),Long.class);
    }
    private String catalogQuery(boolean count) {

        return """
            WITH items AS (
                SELECT COALESCE(d.id,i.id) AS id,COALESCE(d.draft->>'title',i.stem_en,'') AS title,CASE WHEN d.id IS NULL THEN i.difficulty_level ELSE CAST(d.draft->>'difficultyLevel' AS integer) END AS difficulty,
                    COALESCE(NULLIF(d.status,'DRAFT'),'PUBLISHED') AS status,d.id IS NOT NULL AS edited,COALESCE(d.draft->>'kind',i.kind) AS type,
                    COALESCE(d.draft->>'kind',i.kind)='CLOZE' OR EXISTS(SELECT 1 FROM toeic_placement p WHERE p.item_id=i.id AND p.group_id IS NOT NULL) AS requires_context
                FROM assessment_item i JOIN dataset_active_release a ON a.release_id=i.release_id
                LEFT JOIN content_source_alias alias ON alias.namespace='assessment' AND alias.source_id=i.id
                LEFT JOIN content_source_binding binding ON binding.namespace=alias.namespace AND binding.document_id=alias.document_id AND binding.enabled
                LEFT JOIN content_document d ON d.namespace='assessment' AND d.id=COALESCE(binding.document_id,i.id)
                  AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace=d.namespace AND off.document_id=d.id AND NOT off.enabled)
                WHERE a.domain='grammar-toeic'
                UNION ALL SELECT d.id,COALESCE(d.draft->>'title',''),CAST(d.draft->>'difficultyLevel' AS integer),d.status,true,d.draft->>'kind',COALESCE(d.draft->>'kind'='CLOZE',false)
                FROM content_document d WHERE d.namespace='assessment' AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace=d.namespace AND off.document_id=d.id AND NOT off.enabled) AND NOT EXISTS(SELECT 1 FROM assessment_item i WHERE i.id=d.id)
            ) SELECT %s FROM items WHERE (:q='' OR title ILIKE :pattern)
                AND (CAST(:difficulty AS integer) IS NULL OR difficulty=:difficulty) AND (:status='' OR status=:status)
            %s
            """ .formatted(count ? "count(*)" : "*", count ? "" : "ORDER BY id LIMIT :size OFFSET :offset");
    }
    private MapSqlParameterSource parameters(String query,Integer difficulty,String status,int page,int size) {
        return new MapSqlParameterSource().addValue("q",query).addValue("pattern","%"+query+"%")
            .addValue("status",status).addValue("difficulty",difficulty).addValue("size",size).addValue("offset",page*size);
    }

    public List<Map<String,Object>> usage(UUID id) {
        return jdbc.queryForList("""
            SELECT DISTINCT 'TOEIC' AS domain,t.catalog_id AS id,t.name AS title FROM toeic_placement p
                JOIN toeic_test_version t ON t.id=p.test_version_id JOIN dataset_active_release a ON a.release_id=t.release_id
                WHERE p.item_id=:id
            UNION ALL SELECT DISTINCT CASE WHEN s.id IS NOT NULL THEN 'grammar-subtopic' WHEN t.id IS NOT NULL THEN 'grammar-topic' ELSE 'grammar-bank' END,COALESCE(s.catalog_id,t.catalog_id,b.catalog_id),COALESCE(s.title_vi,t.title_vi,b.name)
                FROM grammar_membership m JOIN dataset_active_release a ON a.release_id=m.release_id
                LEFT JOIN grammar_topic_version t ON t.id=m.topic_version_id
                LEFT JOIN grammar_subtopic_version s ON s.id=m.subtopic_version_id
                LEFT JOIN grammar_bank_version b ON b.id=m.bank_version_id WHERE m.item_id=:id
            UNION ALL SELECT d.namespace,d.id,d.draft->>'title' FROM content_document d
                WHERE d.namespace IN ('grammar-topic','grammar-subtopic','grammar-bank','toeic')
                AND d.draft::text LIKE :needle
            """,Map.of("id",id,"needle","%"+id+"%"));
    }
}
