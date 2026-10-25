package com.lyreo.lesson.domain.authoring;

import com.lyreo.contracts.errors.RequestValidationException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuthoringText {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*");
    private static final Set<String> ABBREVIATIONS = Set.of("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e");
    private AuthoringText() {}
    public record Token(String text, int start, int end) {}
    public record ObservedWord(String text, long startMs, long endMs) {}

    public static List<Token> tokens(String text) {
        var result = new ArrayList<Token>();
        var matcher = TOKEN.matcher(text);
        while (matcher.find()) result.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        return result;
    }
    public static String lexical(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
    }
    public static String lexicalSequence(String text) {
        return String.join(" ", tokens(text).stream().map(t -> lexical(t.text)).toList());
    }
    public static List<AuthoringSentence> split(String text) {
        var result = new ArrayList<AuthoringSentence>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '.' && c != '!' && c != '?') continue;
            if (c == '.' && i > 0 && i + 1 < text.length()
                && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1))) continue;
            int preceding = i - 1;
            while (preceding >= start && (Character.isLetter(text.charAt(preceding)) || text.charAt(preceding) == '.')) preceding--;
            String previous = text.substring(preceding + 1, i).toLowerCase(Locale.ROOT);
            if (c == '.' && (ABBREVIATIONS.contains(previous)
                || (previous.length() == 1 && Character.isLetter(previous.charAt(0)))
                || previous.matches("(?:[a-z]\\.)+[a-z]"))) continue;
            int end = i + 1;
            while (end < text.length() && "\"'’”)]".indexOf(text.charAt(end)) >= 0) end++;
            if (end < text.length() && !Character.isWhitespace(text.charAt(end))) continue;
            addSentence(result, text.substring(start, end));
            start = end;
            i = end - 1;
        }
        addSentence(result, text.substring(start));
        if (result.isEmpty()) throw new RequestValidationException("Transcript contains no lexical text");
        return result;
    }
    private static void addSentence(List<AuthoringSentence> result, String text) {
        if (!text.isBlank() && !tokens(text).isEmpty())
            result.add(new AuthoringSentence(UUID.randomUUID(), text.strip(), "LEARN"));
    }

    /** Groups only observed intervals; it never estimates a missing token's timestamps. */
    public static void mapTiming(List<AuthoringSentence> sentences, List<ObservedWord> observed, long durationMs) {
        var expected = new StringBuilder();
        sentences.forEach(s -> expected.append(lexical(s.text)));
        var actual = new StringBuilder();
        observed.forEach(w -> actual.append(lexical(w.text)));
        if (expected.isEmpty() || !expected.toString().contentEquals(actual))
            throw new RequestValidationException("Timing token coverage differs from transcript; dedicated alignment is required");
        long previousStart = -1;
        for (var word : observed) {
            if (lexical(word.text).isEmpty() || word.startMs < 0 || word.endMs < word.startMs
                || word.endMs > durationMs || word.startMs < previousStart)
                throw new RequestValidationException("Invalid or non-monotonic timing interval");
            previousStart = word.startMs;
        }
        int observedIndex = 0;
        for (var sentence : sentences) {
            List<Token> tokens = tokens(sentence.text);
            var mapped = new ArrayList<AuthoringSentence.Word>();
            int tokenIndex = 0;
            while (tokenIndex < tokens.size()) {
                int firstToken = tokenIndex;
                int firstObserved = observedIndex;
                var left = new StringBuilder(lexical(tokens.get(tokenIndex++).text));
                var right = new StringBuilder(lexical(observed.get(observedIndex++).text));
                while (!left.toString().contentEquals(right)) {
                    if (left.length() < right.length() && tokenIndex < tokens.size())
                        left.append(lexical(tokens.get(tokenIndex++).text));
                    else if (right.length() < left.length() && observedIndex < observed.size())
                        right.append(lexical(observed.get(observedIndex++).text));
                    else throw new RequestValidationException("Timing cannot safely map across sentence boundaries");
                }
                Token first = tokens.get(firstToken);
                Token last = tokens.get(tokenIndex - 1);
                long begin = observed.get(firstObserved).startMs;
                long end = observed.subList(firstObserved, observedIndex).stream().mapToLong(ObservedWord::endMs).max().orElseThrow();
                mapped.add(new AuthoringSentence.Word(sentence.text.substring(first.start, last.end), begin, end,
                    first.start, last.end, firstToken, tokenIndex));
            }
            sentence.words = List.copyOf(mapped);
            sentence.sourceWords = List.of();
            sentence.coarseStartMs = mapped.getFirst().startMs();
            sentence.coarseEndMs = mapped.getLast().endMs();
            sentence.issues = issues(sentence);
            eligibility(sentence, false);
        }
        if (observedIndex != observed.size()) throw new RequestValidationException("Unused alignment tokens");
    }
    public static List<ObservedWord> coalesce(List<ObservedWord> words) {
        var groups = new ArrayList<ObservedWord>();
        for (var word : words) {
            groups.add(word);
            while (groups.size() > 1) {
                var current = groups.getLast();
                var previous = groups.get(groups.size() - 2);
                boolean overlap = current.startMs < previous.endMs;
                boolean touchingZero = current.startMs <= previous.endMs
                    && (current.startMs == current.endMs || previous.startMs == previous.endMs);
                if (!overlap && !touchingZero) break;
                groups.removeLast();
                groups.set(groups.size() - 1, new ObservedWord(previous.text + " " + current.text,
                    Math.min(previous.startMs, current.startMs), Math.max(previous.endMs, current.endMs)));
            }
        }
        return List.copyOf(groups);
    }
    public static List<String> issues(AuthoringSentence sentence) {
        var issues = new ArrayList<String>();
        int count = tokens(sentence.text).size();
        if (count > 45) issues.add("LONG_SENTENCE");
        if (count < 2) issues.add("SHORT_SENTENCE");
        if (sentence.words.isEmpty()) issues.add("TIMING_MISSING");
        long previousEnd = -1;
        for (var word : sentence.words) {
            if (word.startMs() < previousEnd) issues.add("TIMING_OVERLAP");
            if (word.endMs() <= word.startMs()) issues.add("TIMING_ZERO_DURATION");
            if (word.tokenEnd() - word.tokenStart() > 1) issues.add("GROUPED_TIMING");
            previousEnd = word.endMs();
        }
        return issues.stream().distinct().toList();
    }
    public static void eligibility(AuthoringSentence sentence, boolean aligned) {
        boolean valid = aligned && "LEARN".equals(sentence.usage) && !sentence.words.isEmpty()
            && sentence.issues.stream().noneMatch(i -> i.startsWith("TIMING_"));
        sentence.dictationEligible = valid;
        sentence.shadowingEligible = valid;
    }
    public static List<ObservedWord> observed(Map<String,Object> output) {
        Object rawWords=output.containsKey("words") ? output.get("words") : output.get("timestamps");
        if (!(rawWords instanceof List<?> words))
            throw new RequestValidationException("Provider did not return word timestamps");
        var result = new ArrayList<ObservedWord>();
        for (Object value : words) {
            if (!(value instanceof Map<?,?> word)) throw new RequestValidationException("Malformed word timestamp");
            Object text = word.containsKey("text") ? word.get("text") : word.get("word");
            long start = milliseconds(word, "start_ms", "start_time");
            long end = milliseconds(word, "end_ms", "end_time");
            if (!(text instanceof String string)) throw new RequestValidationException("Missing timestamp word");
            if (!lexical(string).isEmpty()) result.add(new ObservedWord(string, start, end));
        }
        return result;
    }
    public static void seedCoarse(List<AuthoringSentence> sentences,Map<String,Object> output) {
        if (!(output.get("segments") instanceof List<?> raw)) return;
        var segments = new ArrayList<ObservedWord>();
        for (var value : raw) {
            if (!(value instanceof Map<?,?> item) || !(item.get("text") instanceof String text)) return;
            try { segments.add(new ObservedWord(text,milliseconds(item,"start_ms","start_time"),milliseconds(item,"end_ms","end_time"))); }
            catch (RequestValidationException invalid) { return; }
        }
        String expected=sentences.stream().map(s->lexical(s.text)).reduce("",String::concat);
        String actual=segments.stream().map(s->lexical(s.text)).reduce("",String::concat);
        if (!expected.equals(actual)) return;
        int sentenceOffset=0;
        for(var sentence:sentences) {
            int end=sentenceOffset+lexical(sentence.text).length();int segmentOffset=0;
            Long first=null,last=null;
            for(var segment:segments) {
                int segmentEnd=segmentOffset+lexical(segment.text).length();
                if(segmentEnd>sentenceOffset && segmentOffset<end && segment.startMs>=0 && segment.endMs>segment.startMs) {
                    if(first==null)first=segment.startMs;last=segment.endMs;
                }
                segmentOffset=segmentEnd;
            }
            if(first!=null && last!=null && sentence.coarseStartMs==null) {
                sentence.coarseStartMs=first;sentence.coarseEndMs=last;
            }
            sentenceOffset=end;
        }
    }
    private static long milliseconds(Map<?,?> word, String ms, String seconds) {
        Object raw = word.containsKey(ms) ? word.get(ms) : word.get(seconds);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new RequestValidationException("Missing or non-finite timestamp");
        double value = number.doubleValue() * (word.containsKey(ms) ? 1 : 1000);
        if (value < 0 || value > Long.MAX_VALUE) throw new RequestValidationException("Timestamp outside supported range");
        return Math.round(value);
    }
}
