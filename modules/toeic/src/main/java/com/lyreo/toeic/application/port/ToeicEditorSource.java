package com.lyreo.toeic.application.port;

import com.lyreo.toeic.domain.ToeicDraft;
import java.util.*;

public interface ToeicEditorSource {
    record Summary(UUID id,String title,Integer year,String type,String status,String accessMode,int questionCount,String qualityStatus) {}
    record PublishedSummary(
        UUID id,
        String title,
        String type,
        Integer year,
        int questionCount,
        Integer listeningDurationSeconds,
        Integer readingDurationSeconds,
        String accessMode,
        String requiredFeatureKey
    ) {}
    record Metadata(ToeicDraft content, int questionCount, boolean sourceBlocked) {
        public Metadata(ToeicDraft content, int questionCount) {
            this(content, questionCount, false);
        }
    }
    record ReviewTarget(UUID sourceId,UUID testId,UUID groupId,String title,int part,int firstQuestion) {}
    List<ReviewTarget> reviewTargets();
    default Optional<Metadata> metadata(UUID id) {
        return find(id).map(data -> new Metadata(data, data.placements().size(),
            data.groups().stream().anyMatch(group -> !group.sourceIssues().isEmpty())));
    }
    Optional<ToeicDraft> find(UUID id);
    List<Summary> list(String query,Integer year,String status,int page,int size);
    default List<Summary> list(String query,Integer year,String status,String type,String access,String quality,int page,int size) {return list(query,year,status,page,size);}
    default List<PublishedSummary> listPublished(int page,int size) {
        return list("",null,"PUBLISHED","FULL_TEST","","",page,size).stream()
            .filter(s -> s.questionCount() == 200)
            .map(s -> new PublishedSummary(
                s.id(), s.title(), s.type(), s.year(), s.questionCount(),
                null, null, s.accessMode(), "FEATURE".equals(s.accessMode()) ? "toeic.full-access" : null
            ))
            .toList();
    }
    default long countPublished() {
        return count("",null,"PUBLISHED","FULL_TEST","","");
    }
    long count(String query,Integer year,String status,String type,String access,String quality);
    String status(UUID id);
}
