package com.lyreo.grammar.domain;
import java.util.*;
public record GrammarCatalog(String title,String titleEn,String description,String descriptionEn,
    UUID parentId,int orderIndex,String accessMode,String requiredFeatureKey,List<QuestionRef> questions) {

    public record QuestionRef(UUID itemId,UUID publicationId) {}
}
