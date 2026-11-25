package com.lyreo.assessment.application.port;

import java.time.Instant;
import java.util.*;

public interface SessionRepository {
    record Row(UUID id,UUID learnerId,String domain,UUID catalogId,String mode,String snapshot,
               String answers,String result,long revision,Instant createdAt,Instant expiresAt,Instant submittedAt) {}
    void insert(UUID id,UUID learnerId,String domain,UUID catalogId,String mode,String snapshot);
    Optional<Row> find(UUID id,UUID learnerId,boolean lock);
    List<Row> history(UUID learnerId,String domain,int page,int size);
    default List<Row> history(UUID learnerId,String domain,String status,UUID catalogId,int page,int size) {
        return history(learnerId,domain,page,size);
    }
    void answers(UUID id,long revision,String answers);
    void submit(UUID id,String result);
}
