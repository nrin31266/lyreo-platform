package com.lyreo.lexicon.infrastructure.persistence;
import com.lyreo.lexicon.application.port.LexiconEditorSource;
import com.lyreo.lexicon.domain.LexiconDraft;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcLexiconEditorSource implements LexiconEditorSource {
    private final NamedParameterJdbcTemplate jdbc;
    private final LexiconDraftMapper mapper;
    private final ObjectMapper json;
    public JdbcLexiconEditorSource(NamedParameterJdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;mapper=new LexiconDraftMapper(json);}
    public Optional<LexiconDraft> source(UUID id) {
        var active=jdbc.query("""
            SELECT e.id FROM lexicon_entry e JOIN dataset_active_release a ON a.release_id=e.release_id
            WHERE a.domain='lexicon' AND EXISTS(SELECT 1 FROM lexicon_entry old
              WHERE old.id=:id AND old.headword_id=e.headword_id AND old.entry_type=e.entry_type)
            """,Map.of("id",id),(rs,n)->rs.getObject(1,UUID.class));
        if(active.isEmpty()) return Optional.empty();
        return new JdbcLexiconRepository(jdbc).findById(active.getFirst()).map(mapper::from).map(draft->{
            var contexts=new HashMap<UUID,Map<String,Object>>();
            jdbc.query("SELECT id,source_context FROM lexicon_translation WHERE entry_id=:entry AND source_context IS NOT NULL",Map.of("entry",active.getFirst()),rs->{
                contexts.put(rs.getObject(1,UUID.class),json.readValue(rs.getString(2),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            });
            return new LexiconDraft(draft.title(),draft.type(),draft.headwordId(),draft.sourceMetadataJson(),draft.licenseText(),draft.items(),draft.senses(),draft.forms(),draft.pronunciations(),
                draft.translations().stream().map(t->new LexiconDraft.Translation(t.id(),t.itemId(),t.senseId(),t.wordVi(),t.source(),t.sourceScope(),t.sourceSenseQualifier(),t.linkStatus(),t.sourcePos(),t.unlinkedReason(),t.tags(),contexts.get(t.id()),t.origin())).toList());
        });
    }
    public UUID headword(String title) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:title,9127))",Map.of("title",title),rs->{});
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO lexicon_headword(id,language,identity_form) VALUES(:id,'en',:title) ON CONFLICT(language,identity_form) DO NOTHING",Map.of("id",id,"title",title));
        return jdbc.queryForObject("SELECT id FROM lexicon_headword WHERE language='en' AND identity_form=:title",Map.of("title",title),UUID.class);
    }
    public boolean contains(UUID headwordId,String type) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM lexicon_entry e JOIN dataset_active_release a ON a.release_id=e.release_id
              WHERE a.domain='lexicon' AND e.headword_id=:headword AND e.entry_type=:type)
              OR EXISTS(SELECT 1 FROM content_document d WHERE d.namespace='lexicon' AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace=d.namespace AND off.document_id=d.id AND NOT off.enabled)
              AND d.draft->>'headwordId'=:text AND d.draft->>'type'=:type)
            """,Map.of("headword",headwordId,"text",headwordId.toString(),"type",type),Boolean.class));
    }
    public void indexPublication(UUID id,LexiconDraft content) {
        jdbc.update("DELETE FROM content_search_term WHERE namespace='lexicon' AND document_id=:id",Map.of("id",id));
        indexTerm(id,"HEADWORD",content.title());
        for(var form:content.forms()) indexTerm(id,"FORM",form.form());
    }
    private void indexTerm(UUID id,String kind,String text) {
        String display=com.lyreo.lexicon.application.LexiconLookupNormalizer.displayQuery(text);
        String lookup=com.lyreo.lexicon.application.LexiconLookupNormalizer.lookupQuery(display);
        jdbc.update("INSERT INTO content_search_term(namespace,document_id,kind,term,display_form) VALUES('lexicon',:id,:kind,:term,:display) ON CONFLICT DO NOTHING",Map.of("id",id,"kind",kind,"term",lookup,"display",display));
    }
    record Cursor(int rank, UUID id) {
        String encode() {return Base64.getUrlEncoder().withoutPadding().encodeToString((rank+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        static Cursor decode(String value) {
            if(value==null || value.isEmpty()) return new Cursor(-1,new UUID(0,0));
            try {
                if(value.length()>80) throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8).split(":",-1);
                if(parts.length!=2) throw new IllegalArgumentException();
                int rank=Integer.parseInt(parts[0]);if(rank<0 || rank>5) throw new IllegalArgumentException();
                return new Cursor(rank,UUID.fromString(parts[1]));
            } catch(IllegalArgumentException invalid) {throw new com.lyreo.contracts.errors.RequestValidationException("Invalid Lexicon cursor");}
        }
    }
    public List<Summary> list(String query,String type,String translation,String state,String after,int size) {return list(query,type,translation,state,"",after,size);}
    private static final String CANDIDATES = """
            WITH active AS MATERIALIZED (SELECT release_id FROM dataset_active_release WHERE domain='lexicon'),
            matched AS MATERIALIZED (
                SELECT e.id FROM active a JOIN lexicon_entry e ON e.release_id=a.release_id WHERE :q=''
                UNION SELECT e.id FROM active a JOIN lexicon_entry e ON e.release_id=a.release_id WHERE :q<>'' AND e.lookup_form LIKE :prefix ESCAPE '!'
                UNION SELECT f.entry_id FROM active a JOIN lexicon_form f ON f.release_id=a.release_id WHERE :q<>'' AND f.normalized_form LIKE :prefix ESCAPE '!'
                UNION SELECT e.id FROM content_document d
                  LEFT JOIN content_source_alias alias ON alias.namespace=d.namespace AND alias.document_id=d.id
                  JOIN lexicon_entry e ON e.id=COALESCE(alias.source_id,d.id) JOIN active a ON a.release_id=e.release_id
                  WHERE d.namespace='lexicon' AND :q<>'' AND EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'forms') f WHERE lower(f->>'form') LIKE :prefix ESCAPE '!')
            ), candidates AS (
                SELECT COALESCE(d.id,e.id) AS id,e.display_form AS title,COALESCE(d.draft->>'type',e.entry_type) AS type,
                    COALESCE(NULLIF(d.status,'DRAFT'),'PUBLISHED') AS status,d.id IS NOT NULL AS edited,
                    CASE WHEN :q='' THEN 5 WHEN e.display_form=:display THEN 0 WHEN e.lookup_form=:q THEN 1
                      WHEN EXISTS(SELECT 1 FROM lexicon_form f WHERE f.release_id=e.release_id AND f.entry_id=e.id AND f.normalized_form=:q) OR EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'forms') f WHERE lower(f->>'form')=:q) THEN 2
                      WHEN e.lookup_form LIKE :prefix ESCAPE '!' THEN 3 ELSE 4 END AS rank
                FROM matched hit JOIN lexicon_entry e ON e.id=hit.id
                LEFT JOIN content_source_alias alias ON alias.namespace='lexicon' AND alias.source_id=e.id
                LEFT JOIN content_source_binding binding ON binding.namespace=alias.namespace AND binding.document_id=alias.document_id AND binding.enabled
                LEFT JOIN content_document d ON d.namespace='lexicon' AND d.id=COALESCE(binding.document_id,e.id)
                  AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace=d.namespace AND off.document_id=d.id AND NOT off.enabled)
                WHERE true
                AND (:pos='' OR CASE WHEN d.id IS NULL THEN EXISTS(SELECT 1 FROM lexicon_item i WHERE i.entry_id=e.id AND i.part_of_speech=:pos) ELSE EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'items') i WHERE i->>'partOfSpeech'=:pos) END)
                AND (:translation='' OR CASE WHEN d.id IS NULL THEN EXISTS(SELECT 1 FROM lexicon_sense s WHERE s.entry_id=e.id AND s.translation_status=:translation)
                  ELSE EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'senses') s WHERE s->>'translationStatus'=:translation) END)
                UNION ALL
                SELECT d.id,d.draft->>'title',d.draft->>'type',d.status,true,
                    CASE WHEN :q='' THEN 5 WHEN d.draft->>'title'=:display THEN 0 WHEN lower(d.draft->>'title')=:q THEN 1 WHEN EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'forms') f WHERE lower(f->>'form')=:q) THEN 2 WHEN lower(d.draft->>'title') LIKE :prefix ESCAPE '!' THEN 3 ELSE 4 END
                FROM content_document d WHERE d.namespace='lexicon' AND NOT EXISTS(SELECT 1 FROM content_source_binding off WHERE off.namespace=d.namespace AND off.document_id=d.id AND NOT off.enabled)
                AND NOT EXISTS(SELECT 1 FROM lexicon_entry e JOIN active a ON a.release_id=e.release_id WHERE e.id=d.id)
                AND NOT EXISTS(SELECT 1 FROM content_source_alias alias JOIN content_source_binding b ON b.namespace=alias.namespace AND b.document_id=alias.document_id JOIN lexicon_entry e ON e.id=alias.source_id JOIN active a ON a.release_id=e.release_id WHERE alias.namespace='lexicon' AND alias.document_id=d.id AND b.enabled)
                AND (:q='' OR d.draft->>'title' ILIKE :prefix ESCAPE '!' OR EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'forms') f WHERE lower(f->>'form') LIKE :prefix ESCAPE '!'))
                AND (:pos='' OR EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'items') i WHERE i->>'partOfSpeech'=:pos))
                AND (:translation='' OR EXISTS(SELECT 1 FROM jsonb_array_elements(d.draft->'senses') s WHERE s->>'translationStatus'=:translation))
            )
            """;
    private static final String FILTERS = """
SELECT %s FROM candidates WHERE (:type='' OR type=:type)
                AND (:state='' OR status=:state OR (:state='EDITED' AND edited))
                            """;
    public List<Summary> list(String query,String type,String translation,String state,String pos,String after,int size) {
        return browse(query,type,translation,state,pos,after,null,false,0,size);
    }
    private String candidates(String query) {
        return query.isBlank() ? CANDIDATES.replace("FROM matched hit JOIN lexicon_entry e ON e.id=hit.id", "FROM active a JOIN lexicon_entry e ON e.release_id=a.release_id") : CANDIDATES;
    }
    private org.springframework.jdbc.core.namedparam.MapSqlParameterSource parameters(String query,String type,String translation,String state,String pos) {
        String display=com.lyreo.lexicon.application.LexiconLookupNormalizer.displayQuery(query);
        String lookup=com.lyreo.lexicon.application.LexiconLookupNormalizer.lookupQuery(display);
        return new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("q",lookup).addValue("display",display)
            .addValue("prefix",lookup.replace("!","!!").replace("%","!%").replace("_","!_")+"%")
            .addValue("pos",pos).addValue("translation",translation).addValue("type",type).addValue("state",state);
    }
    public long count(String query,String type,String translation,String state,String pos) {
        return jdbc.queryForObject(candidates(query)+FILTERS.formatted("count(*)"),parameters(query,type,translation,state,pos),Long.class);
    }
    public List<Summary> browse(String query,String type,String translation,String state,String pos,String after,String before,boolean tail,int skip,int size) {
        boolean reverse=before!=null || tail;
        var cursor=Cursor.decode(reverse?before:after);
        String order=reverse?"DESC":"ASC";
        var params=parameters(query,type,translation,state,pos).addValue("rank",cursor.rank()).addValue("anchor",cursor.id())
            .addValue("unbounded",tail || (after==null && before==null)).addValue("skip",skip).addValue("size",size);
        var rows=jdbc.query(candidates(query)+", selected AS ("+FILTERS.formatted("*")+"""
AND (:unbounded OR (rank,id) %s (:rank,CAST(:anchor AS uuid))) ORDER BY rank %s,id %s LIMIT :size OFFSET :skip)
            SELECT p.*, CASE WHEN p.edited THEN (SELECT string_agg(DISTINCT i->>'partOfSpeech',', ') FROM jsonb_array_elements(d.draft->'items') i)
                ELSE (SELECT string_agg(DISTINCT i.part_of_speech,', ') FROM lexicon_item i WHERE i.entry_id=p.id) END AS part_of_speech,
                CASE WHEN p.edited THEN jsonb_array_length(d.draft->'senses') ELSE stats.sense_count END AS sense_count,
                CASE WHEN p.edited THEN (SELECT count(*) FROM jsonb_array_elements(d.draft->'senses') s WHERE nullif(trim(s->>'translationVi'),'') IS NOT NULL) ELSE stats.translated_count END AS translated_count,
                CASE WHEN p.edited THEN (SELECT count(*) FROM jsonb_array_elements(d.draft->'senses') s WHERE s->>'translationStatus'='VERIFIED') ELSE stats.verified_count END AS verified_count,
                CASE WHEN p.edited THEN d.draft->'senses'->0->>'definitionEn' ELSE stats.definition END AS definition,
                CASE WHEN p.edited THEN d.draft->'senses'->0->>'translationVi' ELSE stats.translation_vi END AS translation_vi
            FROM selected p LEFT JOIN content_document d ON d.namespace='lexicon' AND d.id=p.id AND p.edited
            LEFT JOIN LATERAL (SELECT count(*) AS sense_count,count(*) FILTER(WHERE nullif(trim(s.translation_vi),'') IS NOT NULL) AS translated_count,
                count(*) FILTER(WHERE s.translation_status='VERIFIED') AS verified_count,
                (array_agg(s.definition_en ORDER BY s.position,s.id))[1] AS definition,
                (array_agg(s.translation_vi ORDER BY s.position,s.id))[1] AS translation_vi FROM lexicon_sense s WHERE s.entry_id=p.id) stats ON NOT p.edited
            ORDER BY p.rank %s,p.id %s
            """.formatted(reverse?"<":">",order,order,order,order),params,
                (rs,n)->new Summary(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("type"),rs.getString("status"),rs.getBoolean("edited"),new Cursor(rs.getInt("rank"),rs.getObject("id",UUID.class)).encode(),rs.getString("part_of_speech"),rs.getInt("sense_count"),rs.getInt("translated_count"),rs.getInt("verified_count"),rs.getString("definition"),rs.getString("translation_vi")));
        if(reverse) Collections.reverse(rows);
        return rows;
    }
}
