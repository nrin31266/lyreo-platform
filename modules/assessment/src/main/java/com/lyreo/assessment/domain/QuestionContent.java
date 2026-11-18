package com.lyreo.assessment.domain;
import java.util.*;
import org.springframework.modulith.NamedInterface;
@NamedInterface(value="content",propagate=false)
public record QuestionContent(String title,String kind,List<Option> options,String correctOption,
    Integer difficultyLevel,String explanationVi,String translationVi,String answerTranslationVi,
    String vocabularyNote,String transcriptEn,List<Media> media,String combinedTranslationVi) {
    public QuestionContent(String title,String kind,List<Option> options,String correctOption,
        Integer difficultyLevel,String explanationVi,String translationVi,String answerTranslationVi,
        String vocabularyNote,String transcriptEn,List<Media> media) {
        this(title,kind,options,correctOption,difficultyLevel,explanationVi,translationVi,answerTranslationVi,
            vocabularyNote,transcriptEn,media,null);
    }
    @NamedInterface(value="content",propagate=false)
    public record Option(String key,String text) {}
    @NamedInterface(value="content",propagate=false)
    public record Media(UUID assetId,String role) {}
}
