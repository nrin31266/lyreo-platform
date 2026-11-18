package com.lyreo.platform.content;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.contracts.errors.RequestValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Technical storage for sparse drafts and immutable publications; owners validate content. */
@Service
public class DocumentStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public DocumentStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    public record Document<T>(UUID id, long revision, String status, UUID publicationId,
                              T draft, Instant updatedAt, String updatedBy, boolean hasUnpublishedChanges) {
        public Document(UUID id,long revision,String status,UUID publicationId,T draft,Instant updatedAt,String updatedBy) {
            this(id,revision,status,publicationId,draft,updatedAt,updatedBy,revision>0 && publicationId==null);
        }
    }
    public record Publication<T>(UUID id, long revision, T content, String reason,
                                 Instant publishedAt, String publishedBy) {}

    public <T> Optional<Document<T>> find(String namespace, UUID id, Class<T> type) {
        id=canonicalId(namespace,id);
        return jdbc.query("SELECT d.*, (p.id IS NULL OR d.draft IS DISTINCT FROM p.content) AS pending_changes FROM content_document d LEFT JOIN content_publication p ON p.id=d.publication_id WHERE d.namespace=:ns AND d.id=:id",
            Map.of("ns", namespace, "id", id), (rs, row) -> new Document<>(rs.getObject("id",UUID.class),
                rs.getLong("revision"), rs.getString("status"), rs.getObject("publication_id", UUID.class),
                read(rs.getString("draft"), type), rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"),rs.getBoolean("pending_changes")))
            .stream().findFirst();
    }

    public <T> Document<T> source(UUID id, T data) {
        return new Document<>(id, 0, "PUBLISHED", null, data, null, null);
    }

    public <T> Document<T> againstSource(Document<T> row,T source,String sourceStatus) {
        if(row.publicationId()!=null) return row;
        String status="DRAFT".equals(row.status()) && "PUBLISHED".equals(sourceStatus)?sourceStatus:row.status();
        return new Document<>(row.id(),row.revision(),status,null,row.draft(),row.updatedAt(),row.updatedBy(),
            !mapper.valueToTree(row.draft()).equals(mapper.valueToTree(source)));
    }

    @Transactional
    public <T> Document<T> save(String namespace, UUID id, long revision, T data, String actor, Class<T> type) {
        id=canonicalId(namespace,id);
        guard(namespace,id);
        var args = new MapSqlParameterSource().addValue("ns", namespace).addValue("id", id)
            .addValue("rev", revision).addValue("data", mapper.writeValueAsString(data)).addValue("actor", actor);
        int changed;
        if (revision == 0) {
            changed = jdbc.update("""
                INSERT INTO content_document(namespace,id,revision,draft,updated_by)
                VALUES(:ns,:id,1,CAST(:data AS jsonb),:actor) ON CONFLICT DO NOTHING
                """, args);
        } else {
            changed = jdbc.update("""
                UPDATE content_document SET revision=revision+1,draft=CAST(:data AS jsonb),
                    publication_id=CASE WHEN EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace=:ns AND b.document_id=:id AND NOT b.enabled) THEN NULL ELSE publication_id END,
                    status=CASE WHEN EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace=:ns AND b.document_id=:id AND NOT b.enabled) THEN 'DRAFT' ELSE status END,
                    updated_by=:actor,updated_at=now()
                WHERE namespace=:ns AND id=:id AND revision=:rev
                """, args);
        }
        if (changed != 1) throw new StateConflictException("Content changed. Reload before saving.");
        jdbc.update("UPDATE content_source_binding SET enabled=true WHERE namespace=:ns AND document_id=:id",Map.of("ns",namespace,"id",id));
        audit(namespace,id,"SAVE",revision+1,actor,null);
        return find(namespace,id,type).orElseThrow();
    }

    @Transactional
    public <T> Document<T> publish(String namespace, UUID id, long revision, String reason, String actor, Class<T> type) {
        id=canonicalId(namespace,id);
        if(reason!=null && reason.length()>2000) throw new RequestValidationException("Publication note must not exceed 2000 characters");
        lock(namespace,id,revision);
        var current=find(namespace,id,type).orElseThrow();
        if(!current.hasUnpublishedChanges()) return current;
        if(reason==null || reason.isBlank()) reason="Published content";
        UUID publication = UUID.randomUUID();
        var args = new MapSqlParameterSource().addValue("ns",namespace).addValue("id",id)
            .addValue("publication",publication).addValue("actor",actor).addValue("reason",reason);
        jdbc.update("""
            INSERT INTO content_publication(id,namespace,document_id,revision,content,reason,published_by)
            SELECT :publication,namespace,id,revision,draft,:reason,:actor FROM content_document
            WHERE namespace=:ns AND id=:id
            """,args);
        jdbc.update("""
            UPDATE content_document SET publication_id=:publication,status=CASE WHEN status IN ('HIDDEN','ARCHIVED') THEN status ELSE 'PUBLISHED' END,revision=revision+1,
                updated_by=:actor,updated_at=now() WHERE namespace=:ns AND id=:id
            """,args);
        audit(namespace,id,"PUBLISH",revision+1,actor,reason);
        return find(namespace,id,type).orElseThrow();
    }

    @Transactional
    public <T> Document<T> status(String namespace, UUID id, long revision, String status, String actor, Class<T> type) {
        return status(namespace,id,revision,status,actor,type,false);
    }

    @Transactional
    public <T> Document<T> status(String namespace, UUID id, long revision, String status, String actor, Class<T> type,boolean sourcePublished) {
        id=canonicalId(namespace,id);
        if (status==null || !List.of("PUBLISHED","HIDDEN","ARCHIVED").contains(status)) throw new RequestValidationException("Invalid publication state");
        lock(namespace,id,revision);
        if ("PUBLISHED".equals(status) && find(namespace,id,type).orElseThrow().publicationId()==null && !sourcePublished)
            throw new StateConflictException("Publish reviewed content first.");
        jdbc.update("""
            UPDATE content_document SET status=:status,revision=revision+1,updated_by=:actor,updated_at=now()
            WHERE namespace=:ns AND id=:id
            """,Map.of("ns",namespace,"id",id,"status",status,"actor",actor));
        audit(namespace,id,status,revision+1,actor,null);
        return find(namespace,id,type).orElseThrow();
    }

    public <T> Optional<Publication<T>> published(String namespace, UUID id, Class<T> type) {
        id=canonicalId(namespace,id);
        return jdbc.query("""
            SELECT p.* FROM content_document d JOIN content_publication p ON p.id=d.publication_id
            WHERE d.namespace=:ns AND d.id=:id AND d.status='PUBLISHED'
              AND NOT EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace=d.namespace AND b.document_id=d.id AND NOT b.enabled)
            """,Map.of("ns",namespace,"id",id),(rs,n)->publication(rs,type)).stream().findFirst();
    }

    public <T> List<Publication<T>> history(String namespace, UUID id, Class<T> type) {
        id=canonicalId(namespace,id);
        return jdbc.query("""
            SELECT * FROM content_publication WHERE namespace=:ns AND document_id=:id
            ORDER BY revision DESC LIMIT 100
            """,Map.of("ns",namespace,"id",id),(rs,n)->publication(rs,type));
    }

    public <T> Publication<T> publication(String namespace, UUID id, UUID publicationId, Class<T> type) {
        id=canonicalId(namespace,id);
        if (publicationId == null) throw new RequestValidationException("Publication ID is required");
        return jdbc.query("""
            SELECT * FROM content_publication WHERE id=:pub AND namespace=:ns AND document_id=:id
            """,Map.of("pub",publicationId,"ns",namespace,"id",id),(rs,n)->publication(rs,type))
            .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Publication not found"));
    }

    public <T> List<Document<T>> documents(String namespace, String query, int page, int size, Class<T> type) {
        return jdbc.query("""
            SELECT id FROM content_document WHERE namespace=:ns
                AND (:q='' OR draft->>'title' ILIKE :pattern)
            ORDER BY updated_at DESC,id LIMIT :size OFFSET :offset
            """,Map.of("ns",namespace,"q",query,"pattern","%"+query+"%","size",size,"offset",page*size),
            (rs,n)->find(namespace,rs.getObject(1,UUID.class),type).orElseThrow());
    }

    @Transactional
    public <T> Document<T> restore(String namespace, UUID id, long revision, UUID publicationId,
                                   String actor, Class<T> type) {
        var previous=publication(namespace,id,publicationId,type);
        var saved=save(namespace,id,revision,previous.content(),actor,type);
        return publish(namespace,id,saved.revision(),"Restore "+publicationId,actor,type);
    }

    public UUID canonicalId(String namespace,UUID id) {
        var aliases=jdbc.query("""
            SELECT a.document_id FROM content_source_alias a JOIN content_source_binding b
              ON b.namespace=a.namespace AND b.document_id=a.document_id
            WHERE a.namespace=:ns AND a.source_id=:id
            """,Map.of("ns",namespace,"id",id),(rs,n)->rs.getObject(1,UUID.class));
        return aliases.isEmpty()?id:aliases.getFirst();
    }

    public boolean sourceSelected(String namespace,UUID id) {
        id=canonicalId(namespace,id);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM content_source_binding WHERE namespace=:ns AND document_id=:id AND NOT enabled)",Map.of("ns",namespace,"id",id),Boolean.class));
    }

    public boolean exists(String namespace, UUID id) { return find(namespace,id,Object.class).isPresent(); }

    public record Target(UUID id,long revision) {}
    @Transactional
    public <T> List<T> bulk(List<Target> targets,java.util.function.Function<Target,T> operation) {
        if(targets==null || targets.isEmpty() || targets.size()>100 || targets.stream().anyMatch(t->t==null || t.id()==null || t.revision()<0)
            || targets.stream().map(Target::id).distinct().count()!=targets.size()) throw new RequestValidationException("Choose 1–100 unique content items with revisions");
        return targets.stream().map(operation).toList();
    }

    public List<Map<String,Object>> usage(UUID id) {
        return jdbc.queryForList("""
            SELECT DISTINCT namespace,id,draft->>'title' AS title FROM content_document WHERE draft::text LIKE :needle
            UNION SELECT DISTINCT namespace,document_id,content->>'title' FROM content_publication WHERE content::text LIKE :needle
            """,Map.of("needle","%"+id+"%"));
    }

    public List<Map<String,Object>> audit(String namespace,UUID id) {
        id=canonicalId(namespace,id);
        return jdbc.queryForList("SELECT action,revision,actor,reason,occurred_at FROM content_audit WHERE namespace=:ns AND document_id=:id ORDER BY id DESC LIMIT 100",Map.of("ns",namespace,"id",id));
    }
    @Transactional
    public void deleteDraft(String namespace,UUID id,long revision,String actor) {
        id=canonicalId(namespace,id);lock(namespace,id,revision);
        var args=Map.of("ns",namespace,"id",id,"needle","%"+id+"%");
        Boolean referenced=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM content_publication WHERE namespace=:ns AND document_id=:id)
              OR EXISTS(SELECT 1 FROM content_source_binding WHERE namespace=:ns AND document_id=:id)
              OR EXISTS(SELECT 1 FROM content_ai_request WHERE namespace=:ns AND document_id=:id)
              OR EXISTS(SELECT 1 FROM content_document WHERE NOT(namespace=:ns AND id=:id) AND draft::text LIKE :needle)
              OR EXISTS(SELECT 1 FROM content_publication WHERE content::text LIKE :needle)
            """,args,Boolean.class);
        if(Boolean.TRUE.equals(referenced)) throw new StateConflictException("Only an unpublished draft without references can be permanently deleted; archive this content instead");
        jdbc.update("DELETE FROM content_search_term WHERE namespace=:ns AND document_id=:id",args);
        jdbc.update("DELETE FROM content_document WHERE namespace=:ns AND id=:id",args);
        audit(namespace,id,"DELETE",revision,actor,"Deleted unpublished draft");
    }
    private void audit(String namespace,UUID id,String action,long revision,String actor,String reason) {
        jdbc.update("INSERT INTO content_audit(namespace,document_id,action,revision,actor,reason) VALUES(:ns,:id,:action,:revision,:actor,:reason)",new MapSqlParameterSource().addValue("ns",namespace).addValue("id",id).addValue("action",action).addValue("revision",revision).addValue("actor",actor).addValue("reason",reason));
    }

    private void lock(String namespace,UUID id,long revision) {
        guard(namespace,id);
        var rows=jdbc.query("SELECT revision FROM content_document WHERE namespace=:ns AND id=:id FOR UPDATE",
            Map.of("ns",namespace,"id",id),(rs,n)->rs.getLong(1));
        if(rows.isEmpty()) throw new ResourceNotFoundException("Draft not found");
        if(rows.getFirst()!=revision) throw new StateConflictException("Content changed. Reload before continuing.");
    }
    /** Hold a dependency stable through a contextual mutation, even before its first sparse edit. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireRevision(String namespace,UUID id,long revision) {
        id=canonicalId(namespace,id);guard(namespace,id);
        var revisions=jdbc.query("SELECT revision FROM content_document WHERE namespace=:ns AND id=:id",Map.of("ns",namespace,"id",id),(rs,n)->rs.getLong(1));
        if(revision<0 || (revisions.isEmpty()?0:revisions.getFirst())!=revision) throw new StateConflictException("Content changed. Reload before continuing.");
    }
    private void guard(String namespace,UUID id) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key","content:"+namespace+":"+id));
    }
    private <T> Publication<T> publication(java.sql.ResultSet rs,Class<T> type) throws java.sql.SQLException {
        return new Publication<>(rs.getObject("id",UUID.class),rs.getLong("revision"),read(rs.getString("content"),type),
            rs.getString("reason"),rs.getTimestamp("published_at").toInstant(),rs.getString("published_by"));
    }
    public <T> T read(String value, Class<T> type) { return mapper.readValue(value,type); }
}
