package com.lyreo.lesson.domain.practice;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lesson.domain.authoring.AuthoringCloze;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic dictation transcript and note masking policy.
 *
 * <p>Unattempted dictation sentences never expose raw expected text, translations, IPA, or notes.
 * Cloze levels reveal context tokens according to {@link AuthoringCloze} targets, and
 * {@code properNounHints} selectively reveals proper names, numbers, and acronyms.</p>
 */
public final class DictationMaskingPolicy {
    public static final String BLANK_TOKEN = "____";

    public enum HintLevel {
        NONE,
        EASY,
        MEDIUM;

        public static HintLevel parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            try {
                return HintLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new RequestValidationException("hintLevel must be NONE, EASY, or MEDIUM");
            }
        }
    }

    public record WordView(
        int position,
        String surfaceText,
        long startMs,
        long endMs,
        int charStart,
        int charEnd,
        int tokenStart,
        int tokenEnd,
        boolean masked
    ) {}

    public record ProperNounHint(int tokenIndex, String surfaceText) {}

    public record MaskedSentence(
        String text,
        String expectedText,
        List<WordView> words,
        List<ProperNounHint> properNounHints,
        String translation,
        String ipa,
        String ipaAccent,
        String ipaVersion,
        List<AuthoringSentence.Annotation> vocabulary,
        List<AuthoringSentence.Annotation> grammar,
        List<AuthoringSentence.Note> notes,
        List<AuthoringSentence.Group> thoughtGroups
    ) {}

    public MaskedSentence apply(
        String rawText,
        AuthoringCloze.Targets clozeTargets,
        List<WordView> rawWords,
        List<AuthoringSentence.IpaReviewIssue> ipaReviewIssues,
        List<AuthoringSentence.Hint> hints,
        List<EntitySpan> entitySpans,
        HintLevel hintLevel,
        boolean properNounHintsEnabled,
        boolean attempted,
        String translation,
        String ipa,
        String ipaAccent,
        String ipaVersion,
        List<AuthoringSentence.Annotation> vocabulary,
        List<AuthoringSentence.Annotation> grammar,
        List<AuthoringSentence.Note> notes,
        List<AuthoringSentence.Group> thoughtGroups
    ) {
        String safeText = rawText == null ? "" : rawText;
        List<AuthoringText.Token> tokens = AuthoringText.tokens(safeText);
        List<WordView> safeWords = rawWords == null ? List.of() : rawWords;
        List<AuthoringSentence.Annotation> safeVocab = vocabulary == null ? List.of() : List.copyOf(vocabulary);
        List<AuthoringSentence.Annotation> safeGrammar = grammar == null ? List.of() : List.copyOf(grammar);
        List<AuthoringSentence.Note> safeNotes = notes == null ? List.of() : List.copyOf(notes);
        List<AuthoringSentence.Group> safeThoughtGroups = thoughtGroups == null ? List.of() : List.copyOf(thoughtGroups);

        if (attempted) {
            List<WordView> revealedWords = safeWords.stream()
                .map(w -> new WordView(
                    w.position(), w.surfaceText(), w.startMs(), w.endMs(),
                    w.charStart(), w.charEnd(), w.tokenStart(), w.tokenEnd(), false))
                .toList();
            return new MaskedSentence(
                safeText,
                safeText,
                revealedWords,
                List.of(),
                translation,
                ipa,
                ipaAccent,
                ipaVersion,
                safeVocab,
                safeGrammar,
                safeNotes,
                safeThoughtGroups
            );
        }

        Set<Integer> entityTokens = detectEntityTokens(safeText, tokens, ipaReviewIssues, hints, entitySpans);
        Set<Integer> maskedTokens = computeMaskedTokens(tokens, clozeTargets, hintLevel, entityTokens, properNounHintsEnabled);

        String maskedText = renderMaskedText(safeText, tokens, maskedTokens);
        List<WordView> maskedWords = maskWords(safeWords, tokens, maskedTokens);
        List<ProperNounHint> revealedEntityHints = properNounHintsEnabled
            ? entityTokens.stream()
                .sorted()
                .filter(idx -> idx >= 0 && idx < tokens.size())
                .map(idx -> new ProperNounHint(idx, tokens.get(idx).text()))
                .toList()
            : List.of();

        return new MaskedSentence(
            maskedText,
            null,
            maskedWords,
            revealedEntityHints,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    public record EntitySpan(int startChar, int endChar, String text) {}

    private static Set<Integer> computeMaskedTokens(
        List<AuthoringText.Token> tokens,
        AuthoringCloze.Targets cloze,
        HintLevel hintLevel,
        Set<Integer> entityTokens,
        boolean properNounHintsEnabled
    ) {
        Set<Integer> masked = new LinkedHashSet<>();
        switch (hintLevel) {
            case NONE -> {
                for (int i = 0; i < tokens.size(); i++) {
                    masked.add(i);
                }
            }
            case EASY -> {
                List<Integer> targets = cloze == null ? List.of() : cloze.easy();
                if (targets == null || targets.isEmpty()) {
                    for (int i = 0; i < tokens.size(); i++) {
                        masked.add(i);
                    }
                } else {
                    for (Integer idx : targets) {
                        if (idx != null && idx >= 0 && idx < tokens.size()) {
                            masked.add(idx);
                        }
                    }
                }
            }
            case MEDIUM -> {
                List<Integer> targets = cloze == null ? List.of() : cloze.medium();
                if (targets == null || targets.isEmpty()) {
                    for (int i = 0; i < tokens.size(); i++) {
                        masked.add(i);
                    }
                } else {
                    for (Integer idx : targets) {
                        if (idx != null && idx >= 0 && idx < tokens.size()) {
                            masked.add(idx);
                        }
                    }
                }
            }
        }
        if (properNounHintsEnabled) {
            masked.removeAll(entityTokens);
        }
        return masked;
    }

    private static Set<Integer> detectEntityTokens(
        String text,
        List<AuthoringText.Token> tokens,
        List<AuthoringSentence.IpaReviewIssue> ipaReviewIssues,
        List<AuthoringSentence.Hint> hints,
        List<EntitySpan> entitySpans
    ) {
        Set<Integer> result = new LinkedHashSet<>();
        if (ipaReviewIssues != null) {
            for (var issue : ipaReviewIssues) {
                if (issue != null && Set.of("NAME", "DATE_FORMAT", "URL").contains(issue.code())) {
                    addOverlappingTokens(tokens, issue.start(), issue.end(), result);
                }
            }
        }
        if (hints != null) {
            for (var hint : hints) {
                if (hint != null && Set.of("ENTITY", "PROPER_NOUN", "DICTATION").contains(hint.kind())) {
                    for (int i = Math.max(0, hint.tokenStart()); i < Math.min(tokens.size(), hint.tokenEnd()); i++) {
                        result.add(i);
                    }
                }
            }
        }
        if (entitySpans != null) {
            for (var span : entitySpans) {
                if (span == null) {
                    continue;
                }
                if (span.startChar() >= 0 && span.endChar() > span.startChar()) {
                    addOverlappingTokens(tokens, span.startChar(), span.endChar(), result);
                } else if (span.text() != null && !span.text().isBlank()) {
                    String needle = span.text().trim().toLowerCase(Locale.ROOT);
                    for (int i = 0; i < tokens.size(); i++) {
                        if (tokens.get(i).text().toLowerCase(Locale.ROOT).equals(needle)) {
                            result.add(i);
                        }
                    }
                }
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            String tokenText = tokens.get(i).text();
            if (isNumericOrAcronym(tokenText) || (i > 0 && isMidSentenceProperNoun(tokenText))) {
                result.add(i);
            }
        }
        return result;
    }

    private static void addOverlappingTokens(
        List<AuthoringText.Token> tokens,
        int startChar,
        int endChar,
        Set<Integer> result
    ) {
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.end() > startChar && token.start() < endChar) {
                result.add(i);
            }
        }
    }

    private static boolean isNumericOrAcronym(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (token.codePoints().anyMatch(Character::isDigit)) {
            return true;
        }
        int letters = 0;
        boolean allUpper = true;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (!Character.isUpperCase(c)) {
                    allUpper = false;
                }
            }
        }
        return letters >= 2 && allUpper;
    }

    private static boolean isMidSentenceProperNoun(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if ("I".equals(token) || token.startsWith("I'") || token.startsWith("I’")) {
            return false;
        }
        return Character.isUpperCase(token.charAt(0));
    }

    private static String renderMaskedText(
        String text,
        List<AuthoringText.Token> tokens,
        Set<Integer> maskedTokens
    ) {
        if (tokens.isEmpty()) {
            return maskedTokens.isEmpty() ? text : BLANK_TOKEN;
        }
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.start() > cursor) {
                out.append(text, cursor, token.start());
            }
            out.append(maskedTokens.contains(i) ? BLANK_TOKEN : token.text());
            cursor = token.end();
        }
        if (cursor < text.length()) {
            out.append(text, cursor, text.length());
        }
        return out.toString();
    }

    private static List<WordView> maskWords(
        List<WordView> words,
        List<AuthoringText.Token> tokens,
        Set<Integer> maskedTokens
    ) {
        if (words.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> charStartToToken = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            charStartToToken.put(tokens.get(i).start(), i);
        }
        List<WordView> masked = new ArrayList<>(words.size());
        for (int w = 0; w < words.size(); w++) {
            WordView word = words.get(w);
            int startTok = word.tokenStart();
            int endTok = word.tokenEnd();
            if (endTok <= startTok) {
                Integer mapped = word.charStart() >= 0 ? charStartToToken.get(word.charStart()) : null;
                if (mapped != null) {
                    startTok = mapped;
                    endTok = mapped + 1;
                } else if (w < tokens.size()) {
                    startTok = w;
                    endTok = w + 1;
                }
            }
            boolean anyMasked = false;
            boolean allMasked = endTok > startTok;
            for (int t = startTok; t < endTok; t++) {
                if (maskedTokens.contains(t)) {
                    anyMasked = true;
                } else {
                    allMasked = false;
                }
            }
            if (!anyMasked) {
                masked.add(new WordView(
                    word.position(), word.surfaceText(), word.startMs(), word.endMs(),
                    word.charStart(), word.charEnd(), startTok, endTok, false));
            } else if (allMasked) {
                masked.add(new WordView(
                    word.position(), BLANK_TOKEN, word.startMs(), word.endMs(),
                    word.charStart(), word.charEnd(), startTok, endTok, true));
            } else {
                List<String> parts = new ArrayList<>();
                for (int t = startTok; t < endTok && t < tokens.size(); t++) {
                    parts.add(maskedTokens.contains(t) ? BLANK_TOKEN : tokens.get(t).text());
                }
                masked.add(new WordView(
                    word.position(), String.join(" ", parts), word.startMs(), word.endMs(),
                    word.charStart(), word.charEnd(), startTok, endTok, true));
            }
        }
        return List.copyOf(masked);
    }
}
