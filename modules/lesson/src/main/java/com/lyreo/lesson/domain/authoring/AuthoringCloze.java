package com.lyreo.lesson.domain.authoring;

import java.util.*;

/** Stable targets derived from teaching spans and lexical coverage, never a random mask. */
public final class AuthoringCloze {
 private AuthoringCloze() {}
 public record Targets(List<Integer> easy,List<Integer> medium,String version,String text,String textHash) {
  public Targets(List<Integer> easy,List<Integer> medium,String version,String text) {this(easy,medium,version,text,AuthoringContent.hash(text));}
 }
 private static final Set<String> FUNCTION=Set.of("a","an","the","i","you","he","she","it","we","they","am","is","are","was","were","be","been","being","do","does","did","have","has","had","to","of","in","on","at","and","or","but","if","as","for","with","this","that","these","those","my","your","his","her","our","their","can","could","may","might","must","shall","should","will","would","not","its","from","by","into","than","then","some","any","all","each","every");
 public static Targets generate(AuthoringSentence sentence) {return generate(sentence,new HashMap<>());}
 public static void generateAll(List<AuthoringSentence> sentences) {
  var repeats=new HashMap<String,Integer>();for(var sentence:sentences) if(!sentence.clozeManual) sentence.cloze=generate(sentence,repeats);
 }
 private static Targets generate(AuthoringSentence sentence,Map<String,Integer> repeats) {
  var tokens=AuthoringText.tokens(sentence.text);var urls=new ArrayList<int[]>();var matcher=java.util.regex.Pattern.compile("(?i)(?:https?://|www\\.)\\S+").matcher(sentence.text);while(matcher.find()) urls.add(new int[]{matcher.start(),matcher.end()});var candidates=new ArrayList<Integer>();
  for(int i=0;i<tokens.size();i++) {
   String word=sentence.text.substring(tokens.get(i).start(),tokens.get(i).end()).toLowerCase(Locale.ROOT);
   final int position=tokens.get(i).start();
   if(urls.stream().noneMatch(range->position>=range[0] && position<range[1]) && word.codePoints().allMatch(Character::isLetter) && word.length()>2 && !FUNCTION.contains(word)) candidates.add(i);
  }
  var notes=new ArrayList<AuthoringSentence.Annotation>();notes.addAll(sentence.vocabulary);notes.addAll(sentence.grammar);
  var selected=new ArrayList<Integer>();var seen=new HashSet<String>();
  int easy=Math.max(1,(int)Math.round(tokens.size()*.2)),medium=Math.max(easy,(int)Math.round(tokens.size()*.4));
  while(!candidates.isEmpty() && selected.size()<Math.min(medium,Math.max(0,tokens.size()-2))) {
   int best=-1;double bestScore=-Double.MAX_VALUE;
   for(int i:candidates) {
    String word=sentence.text.substring(tokens.get(i).start(),tokens.get(i).end()).toLowerCase(Locale.ROOT);
    int distance=selected.stream().mapToInt(j->Math.abs(i-j)).min().orElse(tokens.size());
    double score=(notes.stream().anyMatch(n->n.tokenStart()<=i && n.tokenEnd()>i)?100:0)
      +(seen.contains(word)?-200:0)-Math.min(5,repeats.getOrDefault(word,0))*30+Math.min(distance,5)*10-word.length()*.01;
    if(score>bestScore) {best=i;bestScore=score;}
   }
   selected.add(best);candidates.remove(Integer.valueOf(best));
   seen.add(sentence.text.substring(tokens.get(best).start(),tokens.get(best).end()).toLowerCase(Locale.ROOT));
  }
  if(tokens.size()<4 || selected.size()<easy) return new Targets(List.of(),List.of(),"cloze-v2",sentence.text);
  for(int i:selected) repeats.merge(sentence.text.substring(tokens.get(i).start(),tokens.get(i).end()).toLowerCase(Locale.ROOT),1,Integer::sum);
  var e=selected.subList(0,Math.min(easy,selected.size())).stream().sorted().toList();
  return new Targets(e,selected.stream().sorted().toList(),"cloze-v2",sentence.text);
 }
}
