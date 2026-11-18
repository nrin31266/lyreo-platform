package com.lyreo.grammar.domain;
import java.util.*;
public record GrammarCatalog(String title,String titleEn,String description,String descriptionEn,
    UUID parentId,int orderIndex,String accessMode,String requiredFeatureKey,List<QuestionRef> questions,
    Theory theory) {
    public GrammarCatalog(String title, String titleEn, String description, String descriptionEn,
        UUID parentId, int orderIndex, String accessMode, String requiredFeatureKey, List<QuestionRef> questions) {
        this(title, titleEn, description, descriptionEn, parentId, orderIndex, accessMode, requiredFeatureKey, questions, null);
    }

    public record QuestionRef(UUID itemId,UUID publicationId) {}
    public record Theory(int order, String objectiveVi, String objectiveEn, String ruleVi, String ruleEn,
                         List<Example> examples) {
        public record Example(String en, String vi) {}
    }
}
