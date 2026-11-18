package com.lyreo.platform.content;

import com.lyreo.contracts.errors.*;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable suggestions only. Content owners validate outputs and explicitly apply selected fields. */
@Service
public class AiDraftRequests {
    private final NamedParameterJdbcTemplate jdbc;
    private final BackgroundJobService jobs;
    private final ObjectMapper json;
    public AiDraftRequests(NamedParameterJdbcTemplate jdbc,BackgroundJobService jobs,ObjectMapper json) {
        this.jdbc=jdbc;this.jobs=jobs;this.json=json;
    }
    public record Request(UUID id,String namespace,UUID documentId,long baseRevision,Long acceptedRevision,
                          String operation,Map<String,Object> input,Map<String,Object> output,Map<String,Object> applied,UUID jobId) {}

    @Transactional
    public Request start(String namespace,UUID document,long revision,String operation,Map<String,Object> input,String actor,String jobType) {
        if(revision<1) throw new RequestValidationException("Save the draft before requesting AI suggestions");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key","content.ai."+namespace+document),rs->{});
        var active=jobs.matchingIds(jobType,namespace,Set.of(BackgroundJobStatus.QUEUED,BackgroundJobStatus.RUNNING,BackgroundJobStatus.RETRY_WAIT,BackgroundJobStatus.CANCEL_REQUESTED));
        if(!active.isEmpty() && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM content_ai_request WHERE namespace=:ns AND document_id=:doc AND job_id IN (:jobs))",Map.of("ns",namespace,"doc",document,"jobs",active),Boolean.class)))
            throw new StateConflictException("An AI request is already active for this draft");
        UUID id=UUID.randomUUID();UUID job=jobs.enqueue(jobType,namespace,id,5,1,"{}");
        jdbc.update("""
            INSERT INTO content_ai_request(id,namespace,document_id,base_revision,operation,input,job_id,created_by)
            VALUES(:id,:ns,:doc,:rev,:operation,CAST(:input AS jsonb),:job,:actor)
            """,Map.of("id",id,"ns",namespace,"doc",document,"rev",revision,"operation",operation,"input",json.writeValueAsString(input),"job",job,"actor",actor));
        return get(id,false);
    }

    @SuppressWarnings("unchecked")
    public Request get(UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM content_ai_request WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id),(rs,n)->new Request(
            id,rs.getString("namespace"),rs.getObject("document_id",UUID.class),rs.getLong("base_revision"),rs.getObject("accepted_revision",Long.class),rs.getString("operation"),
            json.readValue(rs.getString("input"),Map.class),rs.getString("output")==null?null:json.readValue(rs.getString("output"),Map.class),
            json.readValue(rs.getString("applied"),Map.class),rs.getObject("job_id",UUID.class)))
            .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("AI suggestion request not found"));
    }

    public List<Request> history(String namespace,UUID document) {
        return jdbc.query("SELECT id FROM content_ai_request WHERE namespace=:ns AND document_id=:doc ORDER BY created_at DESC LIMIT 20",
            Map.of("ns",namespace,"doc",document),(rs,n)->get(rs.getObject(1,UUID.class),false));
    }

    public void requireRevision(Request request,long current,long submitted) {
        long expected=request.acceptedRevision()==null?request.baseRevision():request.acceptedRevision();
        if(current!=expected || submitted!=current) throw new StateConflictException("The draft changed after this AI request; review or request fresh suggestions");
        if(request.output()==null) throw new StateConflictException("AI suggestions are not ready");
    }

    @Transactional
    public void complete(UUID id,UUID job,String worker,Object output) {
        jobs.fenceForCommit(job,worker);
        jdbc.update("UPDATE content_ai_request SET output=CAST(:output AS jsonb) WHERE id=:id AND job_id=:job AND output IS NULL",
            Map.of("id",id,"job",job,"output",json.writeValueAsString(output)));
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void applied(UUID id,long revision,String key,Object result) {
        jdbc.update("UPDATE content_ai_request SET accepted_revision=:rev,applied=applied || CAST(:value AS jsonb) WHERE id=:id",
            Map.of("id",id,"rev",revision,"value",json.writeValueAsString(Map.of(key,result))));
    }
}
