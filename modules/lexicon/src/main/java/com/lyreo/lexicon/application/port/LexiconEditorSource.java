package com.lyreo.lexicon.application.port;
import com.lyreo.lexicon.domain.LexiconDraft;
import java.util.*;
public interface LexiconEditorSource {
    record Summary(UUID id,String title,String type,String status,boolean edited,String cursor,String partOfSpeech,int senseCount,int translatedCount,int verifiedCount,String definition,String translationVi) {}
    Optional<LexiconDraft> source(UUID id);
    default List<Summary> list(String query,String type,String translation,String state,String pos,String after,int size) {return list(query,type,translation,state,after,size);}
    List<Summary> list(String query,String type,String translation,String state,String after,int size);
    List<Summary> browse(String query,String type,String translation,String state,String pos,String after,String before,boolean tail,int skip,int size);
    long count(String query,String type,String translation,String state,String pos);
    UUID headword(String title);
    boolean contains(UUID headwordId,String type);
    void indexPublication(UUID id,LexiconDraft content);
}
