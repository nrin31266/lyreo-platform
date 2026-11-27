package com.lyreo.lesson.domain.authoring;

import com.lyreo.contracts.errors.RequestValidationException;
import java.util.Set;

/** Generation choices are persisted independently of already-created content. */
public record AuthoringOptions(Set<String> activities, Set<String> annotations, String locale,
                               String density, int vocabularyLimit, int grammarLimit,
                               String pronunciationStrategy, String accent, String pronunciationProvider,
                               Double ttsSpeed) {
    public static final Set<String> FEATURES=Set.of("TRANSLATION","LEXICAL","GRAMMAR","PROFILE","ENTITY_HINTS","DICTATION_HINTS","LEARNING_TIPS","THOUGHT_GROUPS");
    public AuthoringOptions {
        activities=activities==null ? Set.of("DICTATION","SHADOWING") : Set.copyOf(activities);
        annotations=annotations==null ? FEATURES : Set.copyOf(annotations);
        locale=locale==null ? "vi-VN" : locale;
        density=density==null ? "AUTO" : density;
        pronunciationStrategy=pronunciationStrategy==null ? "ON_DEMAND" : pronunciationStrategy;
        accent=accent==null ? "US" : accent;
        pronunciationProvider=pronunciationProvider==null ? "LOCAL" : pronunciationProvider;
        double normalizedSpeed=ttsSpeed==null ? 1.0 : ttsSpeed;
        if(!Double.isFinite(normalizedSpeed) || normalizedSpeed<0.6 || normalizedSpeed>1.4) throw new RequestValidationException("Invalid authoring TTS speed");
        ttsSpeed=Math.round(normalizedSpeed*100.0)/100.0;
        if(!Set.of("DICTATION","SHADOWING").containsAll(activities)
            || !FEATURES.containsAll(annotations) || !locale.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
            || !Set.of("AUTO","CUSTOM").contains(density) || vocabularyLimit<0 || vocabularyLimit>100
            || grammarLimit<0 || grammarLimit>30 || !Set.of("DISABLED","ON_DEMAND","PREGENERATE").contains(pronunciationStrategy)
            || !Set.of("US","UK").contains(accent) || !Set.of("LOCAL","AI").contains(pronunciationProvider)) throw new RequestValidationException("Invalid authoring options");
    }
    public AuthoringOptions(Set<String> activities,Set<String> annotations,String locale,String density,int vocabularyLimit,int grammarLimit,String pronunciationStrategy,String accent) {
        this(activities,annotations,locale,density,vocabularyLimit,grammarLimit,pronunciationStrategy,accent,"LOCAL",1.0);
    }
    public AuthoringOptions(Set<String> activities,Set<String> annotations,String locale,String density,int vocabularyLimit,int grammarLimit,String pronunciationStrategy,String accent,String pronunciationProvider) {
        this(activities,annotations,locale,density,vocabularyLimit,grammarLimit,pronunciationStrategy,accent,pronunciationProvider,1.0);
    }
    public double effectiveTtsSpeed() {
        return ttsSpeed==null ? 1.0 : ttsSpeed;
    }
    public AuthoringOptions normalized() {
        var supported=new java.util.HashSet<>(annotations);supported.retainAll(Set.of("TRANSLATION","LEXICAL","GRAMMAR","PROFILE"));
        return new AuthoringOptions(activities,supported,locale,density,vocabularyLimit,grammarLimit,
            "DISABLED".equals(pronunciationStrategy)?"DISABLED":"PREGENERATE",accent,"LOCAL",effectiveTtsSpeed());
    }
    public static AuthoringOptions defaults() {return new AuthoringOptions(null,Set.of("TRANSLATION","LEXICAL","GRAMMAR","PROFILE"),null,"AUTO",20,8,"PREGENERATE","US","LOCAL",1.0);}
}
