package com.lyreo.speechassessment.application;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Cheap deterministic Tier-1 scoring. It intentionally does not pretend to be a
 * phoneme/prosody judge; optional multimodal judging is a separate Tier-2 step.
 */
public final class SpeechScoringPolicy {

    public Scores score(String expectedText, String transcript, Map<String, Object> alignmentOutput) {
        List<String> expected = tokens(expectedText);
        List<String> actual = tokens(transcript);
        int wordAccuracy = levenshteinAccuracy(expected, actual);
        int timing = timingScore(expected.size(), alignmentOutput);
        int fluency = clamp(Math.round(wordAccuracy * 0.65f + timing * 0.35f));
        return new Scores(wordAccuracy, timing, fluency);
    }

    static int levenshteinAccuracy(List<String> expected, List<String> actual) {
        if (expected.isEmpty()) return actual.isEmpty() ? 100 : 0;
        int[][] distance = new int[expected.size() + 1][actual.size() + 1];
        for (int i = 0; i <= expected.size(); i++) distance[i][0] = i;
        for (int j = 0; j <= actual.size(); j++) distance[0][j] = j;
        for (int i = 1; i <= expected.size(); i++) {
            for (int j = 1; j <= actual.size(); j++) {
                int substitution = expected.get(i - 1).equals(actual.get(j - 1)) ? 0 : 1;
                distance[i][j] = Math.min(
                    Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                    distance[i - 1][j - 1] + substitution
                );
            }
        }
        int denominator = Math.max(expected.size(), actual.size());
        return clamp(Math.round(100f * (1f - ((float) distance[expected.size()][actual.size()] / denominator))));
    }

    @SuppressWarnings("unchecked")
    private static int timingScore(int expectedWordCount, Map<String, Object> alignmentOutput) {
        Object rawWords = alignmentOutput == null ? null : alignmentOutput.get("words");
        if (!(rawWords instanceof List<?> words) || words.isEmpty() || expectedWordCount <= 0) {
            return 70; // neutral baseline when the aligner cannot provide usable timing.
        }

        Map<String, Object> first = words.getFirst() instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        Map<String, Object> last = words.getLast() instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
        double startMs = number(first.get("start_ms"));
        double endMs = number(last.get("end_ms"));
        double durationMinutes = Math.max(1_000d, endMs - startMs) / 60_000d;
        double wordsPerMinute = expectedWordCount / durationMinutes;

        // Broad learner-friendly band, not a native-speaker prosody claim.
        if (wordsPerMinute >= 90 && wordsPerMinute <= 190) return 100;
        double distance = wordsPerMinute < 90 ? 90 - wordsPerMinute : wordsPerMinute - 190;
        return clamp((int) Math.round(100 - Math.min(70, distance * 0.8)));
    }

    static List<String> tokens(String value) {
        String normalized = value == null
            ? ""
            : value.toLowerCase().replaceAll("[^a-z0-9']+", " ").trim();
        if (normalized.isBlank()) return List.of();
        return Arrays.stream(normalized.split("\\s+"))
            .filter(token -> !token.isBlank())
            .toList();
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public record Scores(int wordAccuracy, int timingScore, int fluencyScore) {}
}
