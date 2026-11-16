package com.lyreo.lesson;
import com.lyreo.lesson.domain.authoring.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AuthoringFinishTest {
 @Test void normalizedHighlightRetainsOriginalOffsets() {
  var s=new AuthoringSentence(UUID.randomUUID(),"It's  raining, isn't it?","LEARN");
  var note=AuthoringContent.annotation(s,"It’s raining,","Meaning",1,"LEXICAL");
  assertThat(note.surface()).isEqualTo("It's  raining");
  assertThat(note.tokenStart()).isZero();assertThat(note.tokenEnd()).isEqualTo(2);
 }
 @Test void clozeIsDeterministicNestedAndKeepsContext() {
  var s=new AuthoringSentence(UUID.randomUUID(),"Many people have car accidents when it rains.","LEARN");
  var a=AuthoringCloze.generate(s);var b=AuthoringCloze.generate(s);
  assertThat(a).isEqualTo(b);assertThat(a.medium()).containsAll(a.easy());
  assertThat(a.easy()).isNotEmpty();assertThat(a.medium().size()).isLessThan(AuthoringText.tokens(s.text).size());
 }
 @Test void defaultClozeKeepsSimpleModalsAndFunctionWordsVisible() {
  var sentence=new AuthoringSentence(UUID.randomUUID(),"We can learn every day and will not stop practising.","LEARN");
  var targets=AuthoringCloze.generate(sentence);
  assertThat(targets.medium()).isNotEmpty();
  assertThat(targets.medium()).doesNotContain(0,1,3,5,6,7);
 }
 @Test void legacyOptionsNormalizeOnlyOnExplicitUse() {
  var o=new AuthoringOptions(Set.of("SHADOWING"),Set.of("TRANSLATION","LEARNING_TIPS"),"vi-VN","AUTO",20,8,"ON_DEMAND","US");
  assertThat(o.normalized().annotations()).containsExactly("TRANSLATION");
  assertThat(o.annotations()).contains("LEARNING_TIPS");
 }
}
