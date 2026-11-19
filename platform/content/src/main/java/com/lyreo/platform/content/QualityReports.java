package com.lyreo.platform.content;

import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

/** Durable reports; domain handlers own every rule and issue. */
@Service
public class QualityReports {
    public static final String RULES_VERSION="2.1.2";
    private final NamedParameterJdbcTemplate jdbc; private final ObjectMapper json; private final BackgroundJobService jobs;
    public QualityReports(NamedParameterJdbcTemplate jdbc,ObjectMapper json,BackgroundJobService jobs) {this.jdbc=jdbc;this.json=json;this.jobs=jobs;}
    public record Issue(String code,String severity,String location,String message,String href) {}
    public record Report(UUID id,String namespace,UUID targetId,long revision,String fingerprint,String rulesVersion,
        UUID jobId,String status,List<Issue> issues,Instant checkedAt,Map<String,Object> context) {}
    public String fingerprint(Object content) {
        // Conservative invalidation also catches shared question publication and release switches.
        var state=jdbc.queryForList("SELECT namespace,id,revision,publication_id,status FROM content_document ORDER BY namespace,id",Map.of());
        var releases=jdbc.queryForList("SELECT domain,release_id FROM dataset_active_release ORDER BY domain",Map.of());
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of(content,state,releases)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    @Transactional public Report start(String namespace,UUID target,long revision,String fingerprint,String jobType,Map<String,Object> context) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key","quality:"+namespace+":"+target));
        var current=latest(namespace,target,fingerprint);
        if(current.isPresent() && current.get().status().equals("RUNNING")) return current.get();
        UUID id=UUID.randomUUID();UUID job=jobs.enqueue(jobType,namespace,id,5,1,"{}");
        jdbc.update("INSERT INTO content_quality_report(id,namespace,target_id,revision,fingerprint,rules_version,job_id,context) VALUES(:id,:ns,:target,:revision,:fingerprint,:rules,:job,CAST(:context AS jsonb))",
            Map.of("id",id,"ns",namespace,"target",target,"revision",revision,"fingerprint",fingerprint,"rules",RULES_VERSION,"job",job,"context",json.writeValueAsString(context)));
        return get(id);
    }
    public Optional<Report> latest(String namespace,UUID target,String fingerprint) {
        return jdbc.query("SELECT id FROM content_quality_report WHERE namespace=:ns AND target_id=:target ORDER BY created_at DESC,id DESC LIMIT 1",Map.of("ns",namespace,"target",target),(rs,n)->get(rs.getObject(1,UUID.class)))
            .stream().findFirst().map(r->Objects.equals(r.fingerprint(),fingerprint) && RULES_VERSION.equals(r.rulesVersion())?r:withStatus(r,"STALE"));
    }
    public Report get(UUID id) {
        var report=jdbc.query("SELECT * FROM content_quality_report WHERE id=:id",Map.of("id",id),(rs,n)->new Report(id,rs.getString("namespace"),rs.getObject("target_id",UUID.class),rs.getLong("revision"),rs.getString("fingerprint"),rs.getString("rules_version"),rs.getObject("job_id",UUID.class),rs.getString("status"),json.readValue(rs.getString("issues"),new TypeReference<List<Issue>>(){}),rs.getTimestamp("checked_at")==null?null:rs.getTimestamp("checked_at").toInstant(),json.readValue(rs.getString("context"),new TypeReference<Map<String,Object>>(){})))
            .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Quality report not found"));
        var job=jobs.findById(report.jobId());
        if(report.status().equals("RUNNING") && job.isPresent() && Set.of(BackgroundJobStatus.FAILED,BackgroundJobStatus.CANCELLED).contains(job.get().status())) return withStatus(report,"FAILED");
        return report;
    }
    @Transactional public void complete(UUID id,UUID job,String worker,List<Issue> issues) {
        jobs.fenceForCommit(job,worker);
        String status=issues.stream().anyMatch(i->i.severity().equals("ERROR"))?"BLOCKED":issues.isEmpty()?"PASSED":"WARNING";
        jdbc.update("UPDATE content_quality_report SET status=:status,issues=CAST(:issues AS jsonb),checked_at=now() WHERE id=:id AND job_id=:job",
            Map.of("id",id,"job",job,"status",status,"issues",json.writeValueAsString(issues)));
    }
    private Report withStatus(Report r,String status) {return new Report(r.id(),r.namespace(),r.targetId(),r.revision(),r.fingerprint(),r.rulesVersion(),r.jobId(),status,r.issues(),r.checkedAt(),r.context());}
}
