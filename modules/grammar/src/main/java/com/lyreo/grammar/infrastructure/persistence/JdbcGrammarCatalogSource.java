package com.lyreo.grammar.infrastructure.persistence;

import com.lyreo.grammar.application.port.GrammarCatalogSource;
import com.lyreo.grammar.domain.GrammarCatalog;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGrammarCatalogSource implements GrammarCatalogSource {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcGrammarCatalogSource(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static String table(String kind) {return switch(kind){case "topic"->"grammar_topic";case "subtopic"->"grammar_subtopic";case "bank"->"grammar_bank";default->throw new IllegalArgumentException("Invalid Grammar catalog kind");};}
    public Optional<GrammarCatalog> find(String kind,UUID id) {
        String table=table(kind), title=kind.equals("bank")?"v.name":"v.title_vi", english=kind.equals("bank")?"v.name":"v.title_en";
        String description=kind.equals("bank")?"NULL":"v.description_vi",descriptionEn=kind.equals("bank")?"NULL":"v.description_en",parent=kind.equals("subtopic")?"v.topic_catalog_id":"NULL::uuid";
        return jdbc.query("SELECT "+title+" AS title,"+english+" AS english,"+description+" AS description,"+descriptionEn+" AS description_en,"+parent+" AS parent_id,v.order_index,c.access_mode,c.required_feature_key,v.id AS version_id FROM "+table+"_version v JOIN "+table+"_catalog c ON c.id=v.catalog_id JOIN dataset_active_release a ON a.release_id=v.release_id WHERE a.domain='grammar-toeic' AND c.id=:id",Map.of("id",id),(rs,n)-> {
            String field=switch(kind){case "topic"->"topic_version_id";case "subtopic"->"subtopic_version_id";default->"bank_version_id";};
            var questions=jdbc.query("SELECT DISTINCT item_id, min(order_index) AS ordering FROM grammar_membership WHERE "+field+"=:version GROUP BY item_id ORDER BY ordering,item_id",Map.of("version",rs.getObject("version_id",UUID.class)),(r,i)->new GrammarCatalog.QuestionRef(r.getObject(1,UUID.class),null));
            return new GrammarCatalog(rs.getString("title"),rs.getString("english"),rs.getString("description"),rs.getString("description_en"),rs.getObject("parent_id",UUID.class),rs.getInt("order_index"),rs.getString("access_mode"),rs.getString("required_feature_key"),questions);
        }).stream().findFirst();
    }
    public Optional<Metadata> metadata(String kind,UUID id) {
        String table=table(kind),title=kind.equals("bank")?"v.name":"v.title_vi",english=kind.equals("bank")?"v.name":"v.title_en";
        String description=kind.equals("bank")?"NULL":"v.description_vi",descriptionEn=kind.equals("bank")?"NULL":"v.description_en",parent=kind.equals("subtopic")?"v.topic_catalog_id":"NULL::uuid";
        String field=switch(kind){case "topic"->"topic_version_id";case "subtopic"->"subtopic_version_id";default->"bank_version_id";};
        return jdbc.query(("SELECT %s AS title,%s AS english,%s AS description,%s AS description_en,%s AS parent_id,v.order_index,c.access_mode,c.required_feature_key,(SELECT count(DISTINCT item_id) FROM grammar_membership m WHERE m.%s=v.id) AS question_count FROM %s_version v JOIN %s_catalog c ON c.id=v.catalog_id JOIN dataset_active_release a ON a.release_id=v.release_id WHERE a.domain='grammar-toeic' AND c.id=:id").formatted(title,english,description,descriptionEn,parent,field,table,table),Map.of("id",id),(rs,n)->new Metadata(new GrammarCatalog(rs.getString("title"),rs.getString("english"),rs.getString("description"),rs.getString("description_en"),rs.getObject("parent_id",UUID.class),rs.getInt("order_index"),rs.getString("access_mode"),rs.getString("required_feature_key"),List.of()),rs.getInt("question_count"))).stream().findFirst();
    }
    public String status(String kind,UUID id) {return jdbc.queryForObject("SELECT publication_status FROM "+table(kind)+"_catalog WHERE id=:id",Map.of("id",id),String.class);}
    @Override
    public void updateAccess(String kind, UUID id, String accessMode, String requiredFeatureKey) {
        jdbc.update(
            "UPDATE " + table(kind) + "_catalog SET access_mode = :accessMode, required_feature_key = :featureKey, updated_at = now() WHERE id = :id",
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("accessMode", accessMode)
                .addValue("featureKey", requiredFeatureKey)
        );
    }
    public List<Summary> list(String kind,String query,UUID parentId,String status,int page,int size) {return list(kind,query,parentId,status,"",page,size);}
    public List<Summary> list(String kind,String query,UUID parentId,String status,String access,int page,int size) {
        return jdbc.query(catalogQuery(kind,false),parameters(kind,query,parentId,status,access,page,size),
            (rs,n)->new Summary(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("title_en"),kind,rs.getObject("parent_id",UUID.class),rs.getString("status"),rs.getString("access_mode"),rs.getInt("question_count")));
    }
    public long count(String kind,String query,UUID parentId,String status,String access) {
        return jdbc.queryForObject(catalogQuery(kind,true),parameters(kind,query,parentId,status,access,0,1),Long.class);
    }

    @Override
    public List<PublishedSummary> listPublished(String kind, UUID parentId, int page, int size) {
        return jdbc.query(
            publishedQuery(kind, false),
            publishedParameters(kind, parentId, page, size),
            (rs, n) -> new PublishedSummary(
                rs.getObject("id", UUID.class),
                kind,
                rs.getString("title"),
                rs.getString("title_en"),
                rs.getString("description"),
                rs.getString("description_en"),
                rs.getObject("parent_id", UUID.class),
                rs.getInt("question_count"),
                rs.getString("access_mode"),
                rs.getString("required_feature_key"),
                rs.getString("parent_access_mode"),
                rs.getString("parent_required_feature_key")
            )
        );
    }

    @Override
    public long countPublished(String kind, UUID parentId) {
        Long count = jdbc.queryForObject(
            publishedQuery(kind, true),
            publishedParameters(kind, parentId, 0, 1),
            Long.class
        );
        return count == null ? 0L : count;
    }

    private String publishedQuery(String kind, boolean count) {
        String table = table(kind);
        String title = kind.equals("bank") ? "v.name" : "v.title_vi";
        String titleEn = kind.equals("bank") ? "v.name" : "v.title_en";
        String description = kind.equals("bank") ? "NULL::text" : "v.description_vi";
        String descriptionEn = kind.equals("bank") ? "NULL::text" : "v.description_en";
        String parent = kind.equals("subtopic") ? "v.topic_catalog_id" : "NULL::uuid";
        String member = switch (kind) {
            case "topic" -> "topic_version_id";
            case "subtopic" -> "subtopic_version_id";
            default -> "bank_version_id";
        };
        boolean isSubtopic = "subtopic".equals(kind);

        String topicsCte = isSubtopic ? """
            published_topics AS (
                SELECT c.id,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'accessMode' ELSE c.access_mode END AS access_mode,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'requiredFeatureKey' ELSE c.required_feature_key END AS required_feature_key
                FROM grammar_topic_version v
                JOIN grammar_topic_catalog c ON c.id = v.catalog_id
                JOIN dataset_active_release a ON a.release_id = v.release_id
                LEFT JOIN content_document d ON d.namespace = 'grammar-topic' AND d.id = c.id
                    AND NOT EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace = d.namespace AND b.document_id = d.id AND NOT b.enabled)
                LEFT JOIN content_publication p ON p.id = d.publication_id AND d.status = 'PUBLISHED'
                WHERE a.domain = 'grammar-toeic'
                  AND (
                      (d.id IS NOT NULL AND d.status = 'PUBLISHED' AND p.id IS NOT NULL)
                      OR ((d.id IS NULL OR (d.status = 'DRAFT' AND d.publication_id IS NULL)) AND c.publication_status = 'PUBLISHED')
                  )
                UNION ALL
                SELECT d.id,
                    p.content->>'accessMode' AS access_mode,
                    p.content->>'requiredFeatureKey' AS required_feature_key
                FROM content_document d
                JOIN content_publication p ON p.id = d.publication_id
                WHERE d.namespace = 'grammar-topic'
                  AND d.status = 'PUBLISHED'
                  AND NOT EXISTS(SELECT 1 FROM grammar_topic_version v JOIN dataset_active_release a ON a.release_id = v.release_id WHERE a.domain = 'grammar-toeic' AND v.catalog_id = d.id)
                  AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace = d.namespace AND off.document_id = d.id AND NOT off.enabled)
            ),
            """ : "";

        String selectClause = count
            ? "count(*)"
            : isSubtopic
                ? "c.id, c.title, c.title_en, c.description, c.description_en, c.parent_id, c.question_count, c.access_mode, c.required_feature_key, pt.access_mode AS parent_access_mode, pt.required_feature_key AS parent_required_feature_key"
                : "c.id, c.title, c.title_en, c.description, c.description_en, c.parent_id, c.question_count, c.access_mode, c.required_feature_key, NULL::varchar AS parent_access_mode, NULL::varchar AS parent_required_feature_key";

        String joinTopics = isSubtopic ? "JOIN published_topics pt ON pt.id = c.parent_id" : "";
        String orderClause = count ? "" : "ORDER BY c.ordering, c.id LIMIT :size OFFSET :offset";

        return """
            WITH %s published_catalogs AS (
                SELECT c.id,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'title' ELSE %s END AS title,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'titleEn' ELSE %s END AS title_en,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'description' ELSE %s END AS description,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'descriptionEn' ELSE %s END AS description_en,
                    CASE WHEN p.id IS NOT NULL THEN CAST(p.content->>'parentId' AS uuid) ELSE %s END AS parent_id,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'accessMode' ELSE c.access_mode END AS access_mode,
                    CASE WHEN p.id IS NOT NULL THEN p.content->>'requiredFeatureKey' ELSE c.required_feature_key END AS required_feature_key,
                    CASE WHEN p.id IS NOT NULL THEN jsonb_array_length(p.content->'questions')
                         ELSE (SELECT count(DISTINCT item_id) FROM grammar_membership m WHERE m.%s = v.id) END AS question_count,
                    CASE WHEN p.id IS NOT NULL THEN CAST(p.content->>'orderIndex' AS integer) ELSE v.order_index END AS ordering
                FROM %s_version v
                JOIN %s_catalog c ON c.id = v.catalog_id
                JOIN dataset_active_release a ON a.release_id = v.release_id
                LEFT JOIN content_document d ON d.namespace = :ns AND d.id = c.id
                    AND NOT EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace = d.namespace AND b.document_id = d.id AND NOT b.enabled)
                LEFT JOIN content_publication p ON p.id = d.publication_id AND d.status = 'PUBLISHED'
                WHERE a.domain = 'grammar-toeic'
                  AND (
                      (d.id IS NOT NULL AND d.status = 'PUBLISHED' AND p.id IS NOT NULL)
                      OR ((d.id IS NULL OR (d.status = 'DRAFT' AND d.publication_id IS NULL)) AND c.publication_status = 'PUBLISHED')
                  )
                UNION ALL
                SELECT d.id,
                    p.content->>'title' AS title,
                    p.content->>'titleEn' AS title_en,
                    p.content->>'description' AS description,
                    p.content->>'descriptionEn' AS description_en,
                    CAST(p.content->>'parentId' AS uuid) AS parent_id,
                    p.content->>'accessMode' AS access_mode,
                    p.content->>'requiredFeatureKey' AS required_feature_key,
                    jsonb_array_length(p.content->'questions') AS question_count,
                    CAST(p.content->>'orderIndex' AS integer) AS ordering
                FROM content_document d
                JOIN content_publication p ON p.id = d.publication_id
                WHERE d.namespace = :ns
                  AND d.status = 'PUBLISHED'
                  AND NOT EXISTS(SELECT 1 FROM %s_version v JOIN dataset_active_release a ON a.release_id = v.release_id WHERE a.domain = 'grammar-toeic' AND v.catalog_id = d.id)
                  AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace = d.namespace AND off.document_id = d.id AND NOT off.enabled)
            )
            SELECT %s
            FROM published_catalogs c
            %s
            WHERE (CAST(:parent AS uuid) IS NULL OR c.parent_id = :parent)
            %s
            """.formatted(
            topicsCte, title, titleEn, description, descriptionEn, parent, member, table, table, table,
            selectClause, joinTopics, orderClause
        );
    }

    private MapSqlParameterSource publishedParameters(String kind, UUID parentId, int page, int size) {
        return new MapSqlParameterSource()
            .addValue("ns", "grammar-" + kind)
            .addValue("parent", parentId, Types.OTHER)
            .addValue("size", size)
            .addValue("offset", page * size);
    }

    private String catalogQuery(String kind,boolean count) {
        String table=table(kind), title=kind.equals("bank")?"v.name":"v.title_vi", titleEn=kind.equals("bank")?"v.name":"v.title_en", parent=kind.equals("subtopic")?"v.topic_catalog_id":"NULL::uuid";
        String member=switch(kind){case "topic"->"topic_version_id";case "subtopic"->"subtopic_version_id";default->"bank_version_id";};

        return """
            WITH catalogs AS (
            SELECT c.id,COALESCE(d.draft->>'title',%s) AS title,COALESCE(d.draft->>'titleEn',%s) AS title_en,COALESCE(CAST(d.draft->>'parentId' AS uuid),%s) AS parent_id,
                COALESCE(NULLIF(d.status,'DRAFT'),c.publication_status) AS status,COALESCE(d.draft->>'accessMode',c.access_mode) AS access_mode,
                CASE WHEN d.id IS NOT NULL THEN jsonb_array_length(d.draft->'questions')
                  ELSE (SELECT count(DISTINCT item_id) FROM grammar_membership m WHERE m.%s=v.id) END AS question_count,
                COALESCE(CAST(d.draft->>'orderIndex' AS integer),v.order_index) AS ordering
            FROM %s_version v JOIN %s_catalog c ON c.id=v.catalog_id JOIN dataset_active_release a ON a.release_id=v.release_id
            LEFT JOIN content_document d ON d.namespace=:ns AND d.id=c.id AND NOT EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace=d.namespace AND b.document_id=d.id AND NOT b.enabled) WHERE a.domain='grammar-toeic'
            UNION ALL SELECT d.id,d.draft->>'title',d.draft->>'titleEn',CAST(d.draft->>'parentId' AS uuid),d.status,d.draft->>'accessMode',jsonb_array_length(d.draft->'questions'),CAST(d.draft->>'orderIndex' AS integer)
            FROM content_document d WHERE d.namespace=:ns AND NOT EXISTS(SELECT 1 FROM %s_version v JOIN dataset_active_release a ON a.release_id=v.release_id WHERE a.domain='grammar-toeic' AND v.catalog_id=d.id) AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace=d.namespace AND off.document_id=d.id AND NOT off.enabled)
            ) SELECT %s FROM catalogs WHERE (:q='' OR title ILIKE :pattern)
                AND (CAST(:parent AS uuid) IS NULL OR parent_id=:parent) AND (:status='' OR status=:status) AND (:access='' OR access_mode=:access)
            %s
            """ .formatted(title,titleEn,parent,member,table,table,table, count ? "count(*)" : "*", count ? "" : "ORDER BY ordering,id LIMIT :size OFFSET :offset").replace("(:q='' OR title ILIKE :pattern)", "(:q='' OR title ILIKE :pattern OR title_en ILIKE :pattern)");
    }
    private MapSqlParameterSource parameters(String kind,String query,UUID parentId,String status,String access,int page,int size) {
        return new MapSqlParameterSource().addValue("ns","grammar-"+kind)
            .addValue("access",access).addValue("status",status).addValue("q",query).addValue("pattern","%"+query+"%").addValue("parent",parentId).addValue("size",size).addValue("offset",page*size);
    }

}
