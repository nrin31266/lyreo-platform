package com.lyreo.lesson.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic server-side Dictation scoring baseline.
 *
 * <p>MVP uses a relaxed token policy: case and punctuation do not reduce score, while word
 * insertions/deletions/substitutions do. The activity config can later select stricter policies
 * without ever trusting a score submitted by the client.</p>
 */
public final class DictationScoringPolicy {

    public Score score(String expected, String answer) {
        List<String> expectedTokens = tokens(expected);
        List<String> answerTokens = tokens(answer);

        if (expectedTokens.isEmpty()) {
            return new Score(answerTokens.isEmpty() ? 100 : 0, 0, answerTokens.size());
        }

        int distance = levenshtein(expectedTokens, answerTokens);
        int denominator = Math.max(expectedTokens.size(), answerTokens.size());
        int score = denominator == 0
            ? 100
            : (int) Math.round(100d * (1d - ((double) distance / denominator)));

        return new Score(
            Math.max(0, Math.min(100, score)),
            expectedTokens.size(),
            answerTokens.size()
        );
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace('’', '\'')
            .toLowerCase(Locale.ROOT);

        String[] raw = normalized.split("[^\\p{L}\\p{N}']+");
        List<String> result = new ArrayList<>(raw.length);
        for (String token : raw) {
            String cleaned = token.replaceAll("^'+|'+$", "");
            if (!cleaned.isBlank()) result.add(cleaned);
        }
        return List.copyOf(result);
    }

    private static int levenshtein(List<String> left, List<String> right) {
        int[] previous = new int[right.size() + 1];
        for (int j = 0; j <= right.size(); j++) previous[j] = j;

        for (int i = 1; i <= left.size(); i++) {
            int[] current = new int[right.size() + 1];
            current[0] = i;
            for (int j = 1; j <= right.size(); j++) {
                int substitution = previous[j - 1]
                    + (left.get(i - 1).equals(right.get(j - 1)) ? 0 : 1);
                current[j] = Math.min(
                    Math.min(previous[j] + 1, current[j - 1] + 1),
                    substitution
                );
            }
            previous = current;
        }
        return previous[right.size()];
    }

    public record Score(int percent, int expectedTokenCount, int answerTokenCount) {}
}
