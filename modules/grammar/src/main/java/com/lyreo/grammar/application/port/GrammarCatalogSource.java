package com.lyreo.grammar.application.port;
import com.lyreo.grammar.domain.GrammarCatalog;
import java.util.*;
public interface GrammarCatalogSource {
    record Summary(UUID id,String title,String kind,UUID parentId,String status,String accessMode,int questionCount) {}
    record Metadata(GrammarCatalog content,int questionCount) {}
    default Optional<Metadata> metadata(String kind,UUID id) {return find(kind,id).map(data->new Metadata(data,data.questions().size()));}
    Optional<GrammarCatalog> find(String kind,UUID id);
    default List<Summary> list(String kind,String query,UUID parentId,int page,int size) { return list(kind,query,parentId,"",page,size); }
    List<Summary> list(String kind,String query,UUID parentId,String status,int page,int size);
    default List<Summary> list(String kind,String query,UUID parentId,String status,String access,int page,int size) {return list(kind,query,parentId,status,page,size);}
    long count(String kind,String query,UUID parentId,String status,String access);
    String status(String kind,UUID id);
}
