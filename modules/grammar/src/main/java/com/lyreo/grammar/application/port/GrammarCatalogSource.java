package com.lyreo.grammar.application.port;

import com.lyreo.grammar.domain.GrammarCatalog;
import java.util.*;

public interface GrammarCatalogSource {
    record Summary(UUID id,String title,String titleEn,String kind,UUID parentId,String status,String accessMode,int questionCount) {
        public Summary(UUID id,String title,String kind,UUID parentId,String status,String accessMode,int questionCount) {
            this(id,title,title,kind,parentId,status,accessMode,questionCount);
        }
    }
    record PublishedSummary(
        UUID id,
        String kind,
        String title,
        String titleEn,
        String description,
        String descriptionEn,
        UUID parentId,
        int questionCount,
        String accessMode,
        String requiredFeatureKey,
        String parentAccessMode,
        String parentRequiredFeatureKey
    ) {}
    record Metadata(GrammarCatalog content,int questionCount) {}
    default Optional<Metadata> metadata(String kind,UUID id) {return find(kind,id).map(data->new Metadata(data,data.questions().size()));}
    Optional<GrammarCatalog> find(String kind,UUID id);
    default List<Summary> list(String kind,String query,UUID parentId,int page,int size) { return list(kind,query,parentId,"",page,size); }
    List<Summary> list(String kind,String query,UUID parentId,String status,int page,int size);
    default List<Summary> list(String kind,String query,UUID parentId,String status,String access,int page,int size) {return list(kind,query,parentId,status,page,size);}
    default List<PublishedSummary> listPublished(String kind,UUID parentId,int page,int size) {
        return list(kind,"",parentId,"PUBLISHED","",page,size).stream()
            .map(s -> new PublishedSummary(
                s.id(), s.kind(), s.title(), s.titleEn(), null, null, s.parentId(),
                s.questionCount(), s.accessMode(), "FEATURE".equals(s.accessMode()) ? "grammar.advanced" : null,
                null, null
            ))
            .toList();
    }
    default long countPublished(String kind,UUID parentId) {
        return count(kind,"",parentId,"PUBLISHED","");
    }
    long count(String kind,String query,UUID parentId,String status,String access);
    String status(String kind,UUID id);
    default void updateAccess(String kind,UUID id,String accessMode,String requiredFeatureKey) {}
}
