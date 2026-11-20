package com.lyreo.toeic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.assessment.api.AssessmentLibrary;
import com.lyreo.assessment.api.AssessmentSessions;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.toeic.application.ToeicEditor;
import com.lyreo.toeic.application.PublishedToeicTests;
import com.lyreo.toeic.application.port.*;
import com.lyreo.toeic.domain.ToeicDraft;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToeicEditorReadinessTest {
    final AssessmentLibrary questions=mock(AssessmentLibrary.class);
    final AssetStore assets=mock(AssetStore.class);
    final DocumentStore documents=mock(DocumentStore.class);
    final ToeicEditor editor=new ToeicEditor(documents,mock(ToeicEditorSource.class),questions,assets,new com.lyreo.toeic.infrastructure.PassageSanitizer());
    final Map<UUID,QuestionContent> content=new HashMap<>();
    final UUID audio=UUID.randomUUID(),image=UUID.randomUUID();

    ToeicDraft fullTest() {
        when(assets.available(anyCollection())).thenReturn(Map.of(audio,new AssetStore.Asset(audio,"audio/wav",10,"audio.wav",false),image,new AssetStore.Asset(image,"image/png",10,"photo.png",false)));
        when(questions.published(any(),nullable(UUID.class))).thenAnswer(call->content.get(call.getArgument(0)));
        var groups=new ArrayList<ToeicDraft.Group>();var placements=new ArrayList<ToeicDraft.Placement>();
        int[] counts={0,6,25,39,30,30,16,54};int number=0;
        for(int part=1;part<=7;part++) {
            UUID group=null;int groupSize=part==6?4:3;
            for(int index=0;index<counts[part];index++) {
                if(Set.of(3,4,6,7).contains(part) && (part==7?Set.of(0,2,4,6,8,11,14,17,21,25,29,34,39,44,49).contains(index):index%groupSize==0)) {
                    group=UUID.randomUUID();
                    groups.add(new ToeicDraft.Group(group,part,part<5?"AUDIO":"TEXT","Group",null,null,null,
                        part==7 && index>=29?List.of(new ToeicDraft.Document(UUID.randomUUID(),1,"PASSAGE","<p>First document</p>"),new ToeicDraft.Document(UUID.randomUUID(),2,"PASSAGE","<p>Second document</p>")):part>=6?List.of(new ToeicDraft.Document(UUID.randomUUID(),1,"PASSAGE","<p>Reading passage</p>")):List.of(),
                        part<5?List.of(new QuestionContent.Media(audio,"audio")):List.of(),true));
                }
                UUID item=UUID.randomUUID();
                var options=new ArrayList<QuestionContent.Option>();
                for(int option=0;option<(part==2?3:4);option++) options.add(new QuestionContent.Option(String.valueOf((char)('A'+option)),"Answer "+option));
                var media=part==1?List.of(new QuestionContent.Media(audio,"audio"),new QuestionContent.Media(image,"image")):part==2?List.of(new QuestionContent.Media(audio,"audio")):List.<QuestionContent.Media>of();
                content.put(item,new QuestionContent(part==6?null:"Question "+(number+1),part==6?"CLOZE":"MCQ_SINGLE",options,"A",2,"Explanation","Bản dịch",null,null,null,media));
                placements.add(new ToeicDraft.Placement(UUID.randomUUID(),item,null,group,part,++number,part==6?number:null));
            }
        }
        return new ToeicDraft("Full test","TEST-1","FULL_TEST",2026,1,2700,4500,"PUBLIC",null,groups,placements);
    }
    @Test void acceptsExactlyOneHundredListeningAndOneHundredReadingQuestions() {
        assertThat(editor.readiness(fullTest())).isEmpty();
        verify(assets).available(argThat(ids->ids.size()==2 && new HashSet<>(ids).equals(Set.of(audio,image))));
        editor.validateStructure(fullTest());
    }
    @Test void previewBatchesImportedQuestionsAndUsesPinnedPublication() {
        var full=fullTest();
        var first=full.placements().getFirst();
        var second=full.placements().get(1);
        var publication=UUID.randomUUID();
        var placements=List.of(first,new ToeicDraft.Placement(second.id(),second.itemId(),publication,
            second.groupId(),second.part(),second.questionNumber(),second.gapNumber()));
        var previewDraft=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),
            2700,4500,"PUBLIC",null,List.of(),placements);
        var testId=UUID.randomUUID();
        when(documents.find("toeic",testId,ToeicDraft.class)).thenReturn(Optional.of(
            new DocumentStore.Document<>(testId,1,"DRAFT",null,previewDraft,null,null)));
        when(questions.importedQuestions(List.of(first.itemId()))).thenReturn(Map.of(first.itemId(),content.get(first.itemId())));
        when(questions.published(second.itemId(),publication)).thenReturn(content.get(second.itemId()));

        var preview=editor.preview(testId);

        assertThat(preview.test()).isEqualTo(previewDraft);
        assertThat(preview.questions()).containsExactlyInAnyOrderEntriesOf(Map.of(
            first.id(),content.get(first.itemId()),second.id(),content.get(second.itemId())));
        verify(questions,never()).published(first.itemId(),null);
    }
    @Test void learnerSnapshotBatchesImportedQuestionsAndKeepsExplicitPublicationPins() {
        var full = fullTest();
        var first = full.placements().getFirst();
        var pinned = UUID.randomUUID();
        var placements = new ArrayList<>(full.placements());
        placements.set(0, new ToeicDraft.Placement(first.id(), first.itemId(), pinned,
            first.groupId(), first.part(), first.questionNumber(), first.gapNumber()));
        var test = new ToeicDraft(full.title(), full.code(), full.type(), full.year(), full.testNumber(),
            2700, 4500, "PUBLIC", null, full.groups(), placements);
        var source = mock(ToeicEditorSource.class);
        var sessions = mock(AssessmentSessions.class);
        var learner = UUID.randomUUID();
        var testId = UUID.randomUUID();
        when(source.find(testId)).thenReturn(Optional.of(test));
        when(source.status(testId)).thenReturn("PUBLISHED");
        var batch = placements.stream().skip(1).map(ToeicDraft.Placement::itemId).toList();
        when(questions.importedQuestions(batch)).thenReturn(content);

        new PublishedToeicTests(documents, source, questions, sessions, null)
            .start(learner, testId, "FULL_TEST", null);

        verify(questions).importedQuestions(batch);
        verify(questions).published(first.itemId(), pinned);
        verifyNoMoreInteractions(questions);
        verify(sessions).start(eq(learner), eq("toeic"), eq(testId), eq("FULL_TEST"),
            argThat(snapshot -> snapshot.items().size() == 200 && pinned.equals(snapshot.items().getFirst().publicationId())));
    }
    @Test void refusesPartialAdminTestsAndDrillsWhileKeepingLearnerPracticeSeparate() {
        var full=fullTest();
        var drill=new ToeicDraft(full.title(),full.code(),"DRILL",full.year(),full.testNumber(),2700,4500,"PUBLIC",null,full.groups(),full.placements());
        assertThatThrownBy(()->editor.save(UUID.randomUUID(),0,drill,"admin")).isInstanceOf(com.lyreo.contracts.errors.RequestValidationException.class).hasMessageContaining("complete 200-question");
        var partial=new ToeicDraft(full.title(),full.code(),"FULL_TEST",full.year(),full.testNumber(),2700,4500,"PUBLIC",null,full.groups(),full.placements().subList(0,100));
        assertThatThrownBy(()->editor.save(UUID.randomUUID(),0,partial,"admin")).isInstanceOf(com.lyreo.contracts.errors.RequestValidationException.class).hasMessageContaining("exactly 200");
    }
    @Test void rejectsSwappingQuestionPositionsInAnOtherwiseCompleteTest() {
        var full = fullTest();
        var id = UUID.randomUUID();
        when(documents.find("toeic", id, ToeicDraft.class)).thenReturn(Optional.of(
            new DocumentStore.Document<>(id, 1, "DRAFT", null, full, null, null)));
        var changed = new ArrayList<>(full.placements());
        var first = changed.get(0);
        var second = changed.get(1);
        changed.set(0, new ToeicDraft.Placement(first.id(), first.itemId(), null, first.groupId(), 1, 2, null));
        changed.set(1, new ToeicDraft.Placement(second.id(), second.itemId(), null, second.groupId(), 1, 1, null));
        var invalid = new ToeicDraft(full.title(), full.code(), full.type(), full.year(), full.testNumber(),
            2700, 4500, "PUBLIC", null, full.groups(), changed);

        assertThatThrownBy(() -> editor.save(id, 1, invalid, "admin"))
            .isInstanceOf(com.lyreo.contracts.errors.RequestValidationException.class)
            .hasMessageContaining("placement");
    }
    @Test void noReviewCheckboxCanOverrideTheActualDocumentStructure() {
        var full=fullTest();var changed=new ArrayList<>(full.groups());
        int index=0;while(changed.get(index).part()!=7 || changed.get(index).documents().size()!=2) index++;
        var group=changed.get(index);
        changed.set(index,new ToeicDraft.Group(group.id(),7,"TEXT",group.title(),null,null,null,List.of(group.documents().getFirst()),List.of(),true));
        var invalid=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,changed,full.placements());
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("SINGLE_PASSAGE_COUNT","PART7_DISTRIBUTION");
        changed.set(index,new ToeicDraft.Group(group.id(),7,"TEXT",group.title(),null,null,null,group.documents(),List.of(new QuestionContent.Media(audio,"audio")),false));
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("READING_AUDIO");
    }
    @Test void rejectsMissingQuestionsAndWrongPartOptionCounts() {
        var full=fullTest();var first=full.placements().get(6);var q=content.get(first.itemId());
        content.put(first.itemId(),new QuestionContent(q.title(),q.kind(),List.of(new QuestionContent.Option("A","One"),new QuestionContent.Option("B","Two"),new QuestionContent.Option("C","Three"),new QuestionContent.Option("D","Four")),q.correctOption(),2,"Explanation",null,null,null,null,q.media()));
        var shortTest=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,full.groups(),full.placements().subList(0,199));
        assertThat(editor.readiness(shortTest)).extracting(ToeicEditor.Issue::code).contains("PART_DISTRIBUTION","OPTION_COUNT");
    }
    @ParameterizedTest @ValueSource(strings={"ABD","ACD"})
    void partTwoWrongOptionKeysBlockPublicationAtTheQuestionLocation(String keys) {
        var full=fullTest();var first=full.placements().get(6);var q=content.get(first.itemId());
        var options=keys.chars().mapToObj(key->new QuestionContent.Option(String.valueOf((char)key),"Answer")).toList();
        content.put(first.itemId(),new QuestionContent(q.title(),q.kind(),options,"A",q.difficultyLevel(),q.explanationVi(),q.translationVi(),null,null,null,q.media()));
        assertThat(editor.readiness(full)).filteredOn(issue->issue.code().equals("OPTION_KEYS"))
            .singleElement().satisfies(issue->{assertThat(issue.severity()).isEqualTo("ERROR");assertThat(issue.location()).isEqualTo("Question 7");});
    }
    @Test void restoreCannotRepublishAnOldPartialTestOrUnavailableMedia() {
        var full=fullTest();var id=UUID.randomUUID();var publication=UUID.randomUUID();
        var partial=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,full.groups(),full.placements().subList(0,100));
        when(documents.publication("toeic",id,publication,ToeicDraft.class)).thenReturn(new DocumentStore.Publication<>(publication,1,partial,"Old content",null,"admin"));
        assertThatThrownBy(()->editor.restore(id,3,publication,"admin")).isInstanceOf(com.lyreo.contracts.errors.RequestValidationException.class).hasMessageContaining("exactly 200");
        when(documents.publication("toeic",id,publication,ToeicDraft.class)).thenReturn(new DocumentStore.Publication<>(publication,1,full,"Old content",null,"admin"));
        when(assets.available(anyCollection())).thenReturn(Map.of(image,new AssetStore.Asset(image,"image/png",10,"photo.png",false)));
        assertThatThrownBy(()->editor.restore(id,3,publication,"admin")).isInstanceOf(com.lyreo.contracts.errors.StateConflictException.class).hasMessageContaining("before restoring");
        verify(documents,never()).restore(anyString(),any(),anyLong(),any(),anyString(),any());
    }
    @Test void restoresACompleteCheckedPublicationWithItsOriginalQuestionReferences() {
        var full=fullTest();var id=UUID.randomUUID();var publication=UUID.randomUUID();
        when(documents.publication("toeic",id,publication,ToeicDraft.class)).thenReturn(new DocumentStore.Publication<>(publication,1,full,"Old content",null,"admin"));
        var restored=new DocumentStore.Document<>(id,5,"PUBLISHED",UUID.randomUUID(),full,null,"admin",false);
        when(documents.restore("toeic",id,3,publication,"admin",ToeicDraft.class)).thenReturn(restored);
        assertThat(editor.restore(id,3,publication,"admin")).isEqualTo(restored);
    }
    @Test void rejectsAQuestionMovedOutOfItsStimulusGroup() {
        var full=fullTest();var changed=new ArrayList<>(full.placements());var placement=changed.get(31);
        changed.set(31,new ToeicDraft.Placement(placement.id(),placement.itemId(),null,null,3,32,null));
        var invalid=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,full.groups(),changed);
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("MISSING_GROUP","GROUP_QUESTION_COUNT");
    }
    @Test void clozeRequiresItsPartSixPassageAndUniqueGapReference() {
        var full=fullTest();var changed=new ArrayList<>(full.placements());var placement=changed.get(130);
        changed.set(130,new ToeicDraft.Placement(placement.id(),placement.itemId(),null,placement.groupId(),6,131,null));
        var invalid=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,full.groups(),changed);
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("GAP_REFERENCE");
        changed.set(130,new ToeicDraft.Placement(placement.id(),placement.itemId(),null,placement.groupId(),6,131,132));
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("GAP_REFERENCE");
        changed.set(130,new ToeicDraft.Placement(placement.id(),placement.itemId(),null,null,6,131,131));
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("MISSING_GROUP");
    }

    @Test void emptyEditorMarkupAndWrongPartSixKindCannotPassQuality() {
        var full=fullTest();var changed=new ArrayList<>(full.groups());
        int index=0;while(changed.get(index).part()!=6) index++;
        var group=changed.get(index);
        changed.set(index,new ToeicDraft.Group(group.id(),6,"TEXT",group.title(),null,null,null,List.of(new ToeicDraft.Document(UUID.randomUUID(),1,"PASSAGE","<p>&nbsp;<br></p><table><tr><td> </td></tr></table>")),List.of(),true));
        var first=full.placements().stream().filter(p->p.part()==6).findFirst().orElseThrow();var q=content.get(first.itemId());
        content.put(first.itemId(),new QuestionContent("A question","MCQ_SINGLE",q.options(),q.correctOption(),1,q.explanationVi(),q.translationVi(),null,null,null,List.of()));
        var invalid=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,changed,full.placements());
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("MISSING_PASSAGE","QUESTION_KIND");
    }

    @Test void optionalVietnameseTranslationAndExplanationDoNotGenerateIssues() {
        var full=fullTest();var first=full.placements().getFirst();var q=content.get(first.itemId());
        content.put(first.itemId(),new QuestionContent(q.title(),q.kind(),q.options(),q.correctOption(),1,null,null,null,null,q.transcriptEn(),q.media()));
        assertThat(editor.readiness(full)).extracting(ToeicEditor.Issue::code).doesNotContain("MISSING_TRANSLATION","MISSING_EXPLANATION");
    }

    @Test void flagsOnlyPresentPartSevenTranslationsWithDifferentMarkerOrder() {
        var full=fullTest();
        var groups=new ArrayList<>(full.groups());
        var original=groups.stream().filter(group->group.part()==7).findFirst().orElseThrow();
        var documents=List.of(new ToeicDraft.Document(UUID.randomUUID(),1,"PASSAGE","<p>One [1]. Two [2].</p>"));
        int index=groups.indexOf(original);
        groups.set(index,new ToeicDraft.Group(original.id(),7,"TEXT",original.title(),null,"Two [2]. One [1].",null,documents,List.of(),true));
        var test=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,groups,full.placements());
        assertThat(editor.readiness(test)).anyMatch(issue->issue.code().equals("TRANSLATION_MARKERS") &&
            issue.location().equals(original.id().toString()) && issue.severity().equals("WARNING"));
        groups.set(index,new ToeicDraft.Group(original.id(),7,"TEXT",original.title(),null,"One [1]. Two [2].",null,documents,List.of(),true));
        assertThat(editor.readiness(test)).extracting(ToeicEditor.Issue::code).doesNotContain("TRANSLATION_MARKERS");
    }

    @Test void invalidGroupMediaIsRejectedInsteadOfCrashingTheQualityWorker() {
        var full=fullTest();var changed=new ArrayList<>(full.groups());var group=changed.getFirst();
        changed.set(0,new ToeicDraft.Group(group.id(),group.part(),group.kind(),group.title(),null,null,null,group.documents(),Arrays.asList((QuestionContent.Media)null),false));
        var invalid=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,changed,full.placements());
        assertThatThrownBy(()->editor.readiness(invalid)).isInstanceOf(com.lyreo.contracts.errors.RequestValidationException.class).hasMessageContaining("media");
    }

    @Test void singlePassagesStayBeforeMultiplePassagesInPartSeven() {
        var full=fullTest();var changed=new ArrayList<>(full.placements());
        var first=changed.get(146);var multiple=changed.get(175);
        changed.set(146,new ToeicDraft.Placement(first.id(),first.itemId(),null,multiple.groupId(),7,147,null));
        changed.set(175,new ToeicDraft.Placement(multiple.id(),multiple.itemId(),null,first.groupId(),7,176,null));
        var invalid=new ToeicDraft(full.title(),full.code(),full.type(),full.year(),full.testNumber(),2700,4500,"PUBLIC",null,full.groups(),changed);
        assertThat(editor.readiness(invalid)).extracting(ToeicEditor.Issue::code).contains("PART7_ORDER");
    }
}
