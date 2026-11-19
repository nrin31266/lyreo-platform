package com.lyreo.assessment.application.port;
import com.lyreo.assessment.domain.QuestionContent;
import java.util.*;
public interface QuestionSource {
    record Summary(UUID id,String title,Integer difficultyLevel,String status,boolean edited,String type,boolean requiresContext) {}
    boolean requiresContext(UUID id);
    List<Map<String,Object>> context(UUID id);
    Optional<QuestionContent> find(UUID id);
    default Map<UUID,QuestionContent> findAll(Collection<UUID> ids) {
        var found=new HashMap<UUID,QuestionContent>();
        ids.forEach(id->find(id).ifPresent(content->found.put(id,content)));
        return found;
    }
    default Optional<QuestionContent> current(UUID id) {return find(id);}
    default List<Summary> list(String query,Integer difficulty,int page,int size) { return list(query,difficulty,"",page,size); }
    List<Summary> list(String query,Integer difficulty,String status,int page,int size);
    long count(String query,Integer difficulty,String status);
    List<Map<String,Object>> usage(UUID id);
}
