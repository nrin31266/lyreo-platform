package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.lesson.application.authoring.AuthoringSemantic;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lexicon.api.LexiconLinkService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuthoringSemanticTest {
    private final LexiconLinkService lexicon=mock(LexiconLinkService.class);
    private final AuthoringSemantic semantic=new AuthoringSemantic(new ObjectMapper(),lexicon);
    @Test void resolvesUniqueExactSpansAndIndependentPriorities() {
        UUID headword=UUID.randomUUID();when(lexicon.resolve("call off")).thenReturn(Optional.of(headword));
        var sentence=new AuthoringSentence(UUID.randomUUID(),"We called off the meeting.","LEARN");
        sentence.dictationEligible=true;sentence.shadowingEligible=true;
        var item=item(sentence);
        item.put("dictationPriority","MEDIUM");
        item.put("vocabulary",List.of(Map.of("surface","called off","canonicalForm","call off","label","PHRASAL_VERB",
            "explanation","hủy","importance","HIGH")));
        item.put("thoughtGroups",List.of("We called off","the meeting."));
        semantic.apply(Map.of("structured",Map.of("targetLocale","vi-VN","items",List.of(item))),List.of(sentence));
        assertThat(sentence.vocabulary.getFirst().headwordId()).isEqualTo(headword);
        assertThat(sentence.vocabulary.getFirst().tokenStart()).isEqualTo(1);
        assertThat(sentence.vocabulary.getFirst().tokenEnd()).isEqualTo(3);
        assertThat(sentence.dictationSelected).isFalse();assertThat(sentence.shadowingSelected).isTrue();
        assertThat(sentence.thoughtGroups).hasSize(2);
    }
    @Test void rejectsAmbiguousPartialAndInventedSurfaces() {
        assertThatThrownBy(()->AuthoringSemantic.span("go go","go")).hasMessageContaining("ambiguous");
        assertThatThrownBy(()->AuthoringSemantic.span("calling","call")).hasMessageContaining("cuts");
        assertThatThrownBy(()->AuthoringSemantic.span("called","call off")).hasMessageContaining("missing");
    }
    @Test void rejectsMalformedMissingDuplicateAndWrongLocale() {
        var sentence=new AuthoringSentence(UUID.randomUUID(),"A useful sentence.","LEARN");
        assertThatThrownBy(()->semantic.apply(Map.of("content","{broken"),List.of(sentence))).hasMessageContaining("JSON");
        assertThatThrownBy(()->semantic.apply(Map.of("structured",Map.of("targetLocale","vi-VN","items",List.of())),List.of(sentence)))
            .hasMessageContaining("Missing");
        assertThatThrownBy(()->semantic.apply(Map.of("structured",Map.of("targetLocale","en-US","items",List.of(item(sentence)))),List.of(sentence)))
            .hasMessageContaining("locale");
    }
    private static java.util.HashMap<String,Object> item(AuthoringSentence sentence) {
        var item=new java.util.HashMap<String,Object>();
        item.put("sentenceId",sentence.id.toString());item.put("translation","Một câu hữu ích.");
        item.put("vocabulary",List.of());item.put("grammar",List.of());item.put("hints",List.of());item.put("notes",List.of());
        item.put("thoughtGroups",List.of());item.put("dictationPriority","HIGH");item.put("shadowingPriority","HIGH");return item;
    }
}
