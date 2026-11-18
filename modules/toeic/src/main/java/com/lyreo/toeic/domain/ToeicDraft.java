package com.lyreo.toeic.domain;
import com.lyreo.assessment.domain.QuestionContent;
import java.util.*;
public record ToeicDraft(String title,String code,String type,Integer year,Integer testNumber,
    Integer listeningDurationSeconds,Integer readingDurationSeconds,String accessMode,String requiredFeatureKey,
    List<Group> groups,List<Placement> placements) {
    public record Document(UUID id,int ordinal,String type,String html) {}
    public record Group(UUID id,int part,String kind,String title,String transcriptEn,String translationVi,
        String vocabularyNote,List<Document> documents,List<QuestionContent.Media> media,boolean reviewed,List<SourceIssue> sourceIssues) {
        public Group(UUID id,int part,String kind,String title,String transcriptEn,String translationVi,String vocabularyNote,List<Document> documents,List<QuestionContent.Media> media,boolean reviewed) {this(id,part,kind,title,transcriptEn,translationVi,vocabularyNote,documents,media,reviewed,List.of());}
    }
    public record SourceIssue(String code,String message) {}
    public record Placement(UUID id,UUID itemId,UUID publicationId,UUID groupId,int part,int questionNumber,Integer gapNumber) {}
}
