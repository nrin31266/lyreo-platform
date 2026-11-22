package com.lyreo.lesson.domain.authoring;

import com.lyreo.contracts.errors.RequestValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Content fingerprints are checkpoints, including successful empty annotation lists. */
public final class AuthoringContent {
    private AuthoringContent() {}
    public static Set<String> features(AuthoringOptions options) {
        var result = new LinkedHashSet<String>();
        for (String feature : List.of("TRANSLATION","LEXICAL","GRAMMAR","PROFILE"))
            if (options.annotations().contains(feature)) result.add(feature);
        result.addAll(new TreeSet<>(options.activities()));
        if (!"DISABLED".equals(options.pronunciationStrategy())) result.add("IPA");
        return result;
    }
    public static int vocabularyLimit(AuthoringLesson lesson) {
        return "CUSTOM".equals(lesson.options.density()) ? lesson.options.vocabularyLimit() : (words(lesson)+74)/75;
    }
    public static int grammarLimit(AuthoringLesson lesson) {
        return "CUSTOM".equals(lesson.options.density()) ? lesson.options.grammarLimit() : (words(lesson)+174)/175;
    }
    public static int words(AuthoringLesson lesson) {
        return lesson.sentences.stream().mapToInt(s -> AuthoringText.tokens(s.text).size()).sum();
    }
    public static String key(AuthoringLesson lesson, int index, String feature) {
        var sentence = lesson.sentences.get(index);
        String text = sentence.text;
        boolean manual = sentence.manualFields.contains(feature);
        String context = "IPA".equals(feature) ? lesson.options.accent() : lesson.options.locale()+"|"
            +(index>0 ? lesson.sentences.get(index-1).text : "")+"|"
            +(index+1<lesson.sentences.size() ? lesson.sentences.get(index+1).text : "");
        // Keep existing manual fingerprints valid when only the generation policy changes.
        boolean manualAuto = manual && "AUTO".equals(lesson.options.density());
        if ("LEXICAL".equals(feature)) context += "|"+(manualAuto ? (words(lesson)+99)/100 : vocabularyLimit(lesson));
        if ("GRAMMAR".equals(feature)) context += "|"+(manualAuto ? (words(lesson)+249)/250 : grammarLimit(lesson));
        String version = switch (feature) {
            case "DICTATION", "SHADOWING" -> "keep-v2";
            case "IPA" -> manual ? "ipa-display-v1" : "ipa-display-v2";
            default -> "content-v1";
        };
        return hash(version+"|"+feature+"|"+context+"|"+text);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String profileKey(AuthoringLesson lesson) {
        return hash("profile-v1|"+lesson.sentences.stream().map(s->s.text).collect(java.util.stream.Collectors.joining("\n")));
    }
    public static boolean current(AuthoringLesson lesson,int index,String feature) {
        if("PROFILE".equals(feature) && lesson.profile.manual) return profileKey(lesson).equals(lesson.profile.key);
        return key(lesson,index,feature).equals(lesson.sentences.get(index).contentKeys.get(feature));
    }
    public static boolean complete(AuthoringLesson lesson) {
        if (lesson.sentences.isEmpty()) return false;
        for (int i=0;i<lesson.sentences.size();i++)
            for (String feature:features(lesson.options)) if (!current(lesson,i,feature)) return false;
        return true;
    }
    public static boolean needed(AuthoringLesson lesson) {
        for (int i=0;i<lesson.sentences.size();i++)
            for (String feature:features(lesson.options))
                if (!current(lesson,i,feature) && !lesson.sentences.get(i).manualFields.contains(feature)
                    && !("PROFILE".equals(feature) && lesson.profile.manual)) return true;
        return false;
    }
    public static AuthoringSentence.Annotation annotation(AuthoringSentence sentence,String surface,String explanation,int occurrence,String kind) {
        require(surface!=null && !surface.isBlank() && surface.length()<=1000,"Invalid annotation surface");
        require(explanation!=null && !explanation.isBlank() && explanation.length()<=500,"Keep annotation explanations within 500 characters");
        require(occurrence>=1 && occurrence<=1000,"Invalid annotation occurrence");
        var tokens=AuthoringText.tokens(sentence.text);
        var wanted=AuthoringText.tokens(surface);require(!wanted.isEmpty(),"Annotation has no lexical tokens");
        var wantedWords=wanted.stream().map(t->normalize(surface.substring(t.start(),t.end()))).toList();
        int found=0;
        for(int i=0;i+wanted.size()<=tokens.size();i++) {
            boolean matches=true;
            for(int j=0;j<wanted.size();j++) if(!normalize(sentence.text.substring(tokens.get(i+j).start(),tokens.get(i+j).end())).equals(wantedWords.get(j))) {matches=false;break;}
            if(matches && !normalize(sentence.text.substring(tokens.get(i).start(),tokens.get(i+wanted.size()-1).end())).equals(normalize(surface.substring(wanted.getFirst().start(),wanted.getLast().end())))) matches=false;
            if(matches && ++found==occurrence) {
                String original=sentence.text.substring(tokens.get(i).start(),tokens.get(i+wanted.size()-1).end());
                return new AuthoringSentence.Annotation(i,i+wanted.size(),original,kind,explanation,"MEDIUM",null,null);
            }
        }
        throw new RequestValidationException("Annotation occurrence does not match complete tokens");
    }
    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).replace('’','\'').replace('‘','\'').replaceAll("[\\s\\p{Z}]+"," ").toLowerCase(Locale.ROOT);
    }
    public static void validateAnnotations(AuthoringSentence sentence,List<AuthoringSentence.Annotation> annotations,int limit) {
        require(annotations!=null && annotations.size()<=limit,"Too many annotations");
        var tokens=AuthoringText.tokens(sentence.text);
        for(var item:annotations) {
            require(item!=null && item.tokenStart()>=0 && item.tokenEnd()>item.tokenStart() && item.tokenEnd()<=tokens.size(),"Invalid annotation span");
            require(sentence.text.substring(tokens.get(item.tokenStart()).start(),tokens.get(item.tokenEnd()-1).end()).equals(item.surface()),"Annotation text does not match its span");
            require(item.explanation()!=null && !item.explanation().isBlank() && item.explanation().length()<=500,"Invalid annotation explanation");
        }
    }
    public static void require(boolean value,String message) { if(!value) throw new RequestValidationException(message); }
}
