package com.lyreo.lesson.domain.authoring;

import java.util.*;

/** Text-based estimates remain editable; they do not certify listening difficulty. */
public final class AuthoringProfile {
    public enum Cefr { A1, A2, B1, B2, C1, C2, UNKNOWN }
    public enum ContentType { CONVERSATION, MONOLOGUE, LECTURE, STORY, NEWS, MIXED, OTHER, UNKNOWN }
    public Cefr cefr=Cefr.UNKNOWN;
    public ContentType contentType=ContentType.UNKNOWN;
    public boolean manual;
    public String key;

    public static AuthoringProfile aggregate(AuthoringLesson lesson) {
        var result=new AuthoringProfile();
        var levels=new EnumMap<Cefr,Integer>(Cefr.class);
        var types=new EnumMap<ContentType,Integer>(ContentType.class);
        for(var sentence:lesson.sentences) {
            int weight=AuthoringText.tokens(sentence.text).size();
            if(sentence.cefrSuggestion!=null && sentence.cefrSuggestion!=Cefr.UNKNOWN) levels.merge(sentence.cefrSuggestion,weight,Integer::sum);
            if(sentence.contentTypeSuggestion!=null && sentence.contentTypeSuggestion!=ContentType.UNKNOWN) types.merge(sentence.contentTypeSuggestion,weight,Integer::sum);
        }
        int total=levels.values().stream().mapToInt(Integer::intValue).sum(),sum=0;
        for(var entry:levels.entrySet()) {sum+=entry.getValue();if(sum*2>=total) {result.cefr=entry.getKey();break;}}
        int typeWords=types.values().stream().mapToInt(Integer::intValue).sum();
        for(var entry:types.entrySet()) if(entry.getValue()*100>=typeWords*60) {result.contentType=entry.getKey();break;}
        if(result.contentType==ContentType.UNKNOWN && !types.isEmpty()) result.contentType=ContentType.MIXED;
        result.key=AuthoringContent.profileKey(lesson);return result;
    }
}
