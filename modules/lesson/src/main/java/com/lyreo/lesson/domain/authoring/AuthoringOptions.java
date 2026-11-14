package com.lyreo.lesson.domain.authoring;

import com.lyreo.contracts.errors.RequestValidationException;
import java.util.Set;

/** Legacy JSON options retained for reading existing drafts and published snapshots. */
public record AuthoringOptions(Set<String> activities, Set<String> annotations, String locale,
                               String density, int vocabularyLimit, int grammarLimit,
                               String pronunciationStrategy, String accent, String pronunciationProvider) {
    public static final Set<String> FEATURES=Set.of("TRANSLATION","LEXICAL","GRAMMAR","ENTITY_HINTS","DICTATION_HINTS","LEARNING_TIPS","THOUGHT_GROUPS");
    public AuthoringOptions {
        activities=activities==null ? Set.of("DICTATION","SHADOWING") : Set.copyOf(activities);
        annotations=annotations==null ? FEATURES : Set.copyOf(annotations);
        locale=locale==null ? "vi-VN" : locale;
        density=density==null ? "AUTO" : density;
        pronunciationStrategy=pronunciationStrategy==null ? "ON_DEMAND" : pronunciationStrategy;
        accent=accent==null ? "US" : accent;
        pronunciationProvider=pronunciationProvider==null ? "LOCAL" : pronunciationProvider;
        if(activities.isEmpty() || !Set.of("DICTATION","SHADOWING").containsAll(activities)
            || !FEATURES.containsAll(annotations) || !locale.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
            || !Set.of("AUTO","CUSTOM").contains(density) || vocabularyLimit<0 || vocabularyLimit>100
            || grammarLimit<0 || grammarLimit>30 || !Set.of("DISABLED","ON_DEMAND","PREGENERATE").contains(pronunciationStrategy)
            || !Set.of("US","UK").contains(accent) || !Set.of("LOCAL","AI").contains(pronunciationProvider)) throw new RequestValidationException("Invalid authoring options");
    }
    public AuthoringOptions(Set<String> activities,Set<String> annotations,String locale,String density,int vocabularyLimit,int grammarLimit,String pronunciationStrategy,String accent) {
        this(activities,annotations,locale,density,vocabularyLimit,grammarLimit,pronunciationStrategy,accent,"LOCAL");
    }
    public static AuthoringOptions defaults() {return new AuthoringOptions(null,null,null,null,12,5,null,null);}
}
