package com.lyreo.assessment.infrastructure.persistence;

import com.lyreo.assessment.application.port.SessionRepository;
import com.lyreo.contracts.errors.StaleRevisionException;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSessionRepository implements SessionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcSessionRepository(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    public void insert(UUID id,UUID learnerId,String domain,UUID catalogId,String mode,String snapshot) {
        jdbc.update("INSERT INTO assessment_session(id,learner_id,domain,catalog_id,mode,snapshot) VALUES(:id,:user,:domain,:catalog,:mode,CAST(:snapshot AS jsonb))",
            new MapSqlParameterSource().addValue("id",id).addValue("user",learnerId).addValue("domain",domain).addValue("catalog",catalogId).addValue("mode",mode).addValue("snapshot",snapshot));
    }
    public Optional<Row> find(UUID id,UUID learnerId,boolean lock) {
        return jdbc.query("SELECT * FROM assessment_session WHERE id=:id AND learner_id=:user"+(lock?" FOR UPDATE":""),Map.of("id",id,"user",learnerId),this::row).stream().findFirst();
    }
    public List<Row> history(UUID learnerId,String domain,int page,int size) {
        return history(learnerId,domain,null,null,page,size);
    }
    public List<Row> history(UUID learnerId,String domain,String status,UUID catalogId,int page,int size) {
        var params = new MapSqlParameterSource()
            .addValue("user", learnerId)
            .addValue("domain", domain)
            .addValue("status", status, Types.VARCHAR)
            .addValue("catalogId", catalogId, Types.OTHER)
            .addValue("size", size)
            .addValue("offset", page * size);
        return jdbc.query("""
            SELECT * FROM assessment_session
             WHERE learner_id = :user
               AND domain = :domain
               AND (CAST(:catalogId AS uuid) IS NULL OR catalog_id = :catalogId)
               AND (
                   CAST(:status AS text) IS NULL
                   OR (:status = 'SUBMITTED' AND submitted_at IS NOT NULL)
                   OR (:status = 'OPEN' AND submitted_at IS NULL AND expires_at > now())
                   OR (:status = 'EXPIRED' AND submitted_at IS NULL AND expires_at <= now())
               )
             ORDER BY created_at DESC, id
             LIMIT :size OFFSET :offset
            """, params, this::row);
    }
    public long countHistory(UUID learnerId,String domain,String status,UUID catalogId) {
        var params = new MapSqlParameterSource()
            .addValue("user", learnerId)
            .addValue("domain", domain)
            .addValue("status", status, Types.VARCHAR)
            .addValue("catalogId", catalogId, Types.OTHER);
        Long total = jdbc.queryForObject("""
            SELECT count(*) FROM assessment_session
             WHERE learner_id = :user
               AND domain = :domain
               AND (CAST(:catalogId AS uuid) IS NULL OR catalog_id = :catalogId)
               AND (
                   CAST(:status AS text) IS NULL
                   OR (:status = 'SUBMITTED' AND submitted_at IS NOT NULL)
                   OR (:status = 'OPEN' AND submitted_at IS NULL AND expires_at > now())
                   OR (:status = 'EXPIRED' AND submitted_at IS NULL AND expires_at <= now())
               )
            """, params, Long.class);
        return total == null ? 0L : total;
    }
    public void answers(UUID id,long revision,String answers) {
        if(jdbc.update("UPDATE assessment_session SET answers=CAST(:answers AS jsonb),revision=revision+1 WHERE id=:id AND revision=:revision AND submitted_at IS NULL",Map.of("answers",answers,"id",id,"revision",revision))!=1) {
            throw new StaleRevisionException("Session changed; reload answers before saving");
        }
    }
    public void submit(UUID id,String result) {jdbc.update("UPDATE assessment_session SET result=CAST(:result AS jsonb),submitted_at=now(),revision=revision+1 WHERE id=:id AND submitted_at IS NULL",Map.of("result",result,"id",id));}
    private Row row(ResultSet rs,int index) throws SQLException {
        var submitted=rs.getTimestamp("submitted_at");
        return new Row(rs.getObject("id",UUID.class),rs.getObject("learner_id",UUID.class),rs.getString("domain"),rs.getObject("catalog_id",UUID.class),rs.getString("mode"),rs.getString("snapshot"),rs.getString("answers"),rs.getString("result"),rs.getLong("revision"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),submitted==null?null:submitted.toInstant());
    }
}
