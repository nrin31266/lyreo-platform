package com.lyreo.toeic.application.port;
import com.lyreo.toeic.domain.ToeicDraft;
import java.util.*;
public interface ToeicEditorSource {
    record Summary(UUID id,String title,Integer year,String type,String status,String accessMode,int questionCount,String qualityStatus) {}
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
    long count(String query,Integer year,String status,String type,String access,String quality);
    String status(UUID id);
}
