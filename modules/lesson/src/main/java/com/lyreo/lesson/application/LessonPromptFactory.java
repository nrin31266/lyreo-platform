package com.lyreo.lesson.application;

import java.util.List;

/**
 * Business prompts live in Java. FastAPI receives an already-defined AI task.
 * Prompt changes alter product semantics, so keep them versioned and testable.
 */
public final class LessonPromptFactory {

    public String translation(List<String> sentences) {
        return """
            Translate each English sentence into natural Vietnamese for an English learner.
            Preserve names and factual meaning. Do not add explanations.
            Return JSON object: {"items":[{"sentence_index":0,"translation_vi":"..."}]}.
            """ + numbered(sentences);
    }

    public String lexicalDetection(List<String> sentences) {
        return """
            You are preparing contextual English-learning annotations for Lyreo.
            Detect only useful words or multi-word expressions worth noticing.
            Prefer phrasal verbs, idioms, collocations and phrases over trivial words.
            Do not invent dictionary senses. Return JSON object {"items":[...]}.
            For each item include: sentence_index, surface_text, canonical_form,
            type (WORD|PHRASE|PHRASAL_VERB|IDIOM|COLLOCATION), context_meaning_vi,
            importance (LOW|MEDIUM|HIGH), confidence (0..1).
            """ + numbered(sentences);
    }

    public String grammarDetection(List<String> sentences) {
        return """
            Analyze the English sentences for learner-relevant grammar.
            Return JSON object {"items":[...]} with sentence_index, surface_text,
            grammar_label, short_explanation_vi, confidence (0..1).
            Prefer mapping to an existing grammar taxonomy when taxonomy labels are supplied.
            Do not generate practice questions; Lyreo uses its curated Grammar Bank.
            """ + numbered(sentences);
    }

    public String thoughtGroups(List<String> sentences) {
        return """
            Split each sentence into natural English shadowing thought groups/chunks.
            Do not rewrite words. Preserve the original order and punctuation as much as possible.
            Return JSON object {"items":[{"sentence_index":0,"groups":["...","..."]}]}.
            Keep groups useful for rhythm and breath, not one word per group.
            """ + numbered(sentences);
    }

    public String learningTips(List<String> sentences) {
        return """
            Produce concise learning tips only where they materially help a Vietnamese English learner.
            Tips may cover natural expression, listening traps, connected speech or common misunderstandings.
            Avoid generic filler. Return JSON object {"items":[{"sentence_index":0,"tip_vi":"...","kind":"LISTENING|EXPRESSION|PRONUNCIATION|USAGE"}]}.
            """ + numbered(sentences);
    }

    public String sentenceIpa(List<String> sentences, String accent) {
        return """
            Produce one careful learner-facing IPA transcription for each complete English sentence.
            Accent: %s. Preserve lexical identity. Return JSON object
            {"items":[{"sentence_index":0,"ipa":"/.../","accent":"%s"}]}.
            """.formatted(accent, accent) + numbered(sentences);
    }

    private static String numbered(List<String> sentences) {
        StringBuilder out = new StringBuilder("\nSentences:\n");
        for (int i = 0; i < sentences.size(); i++) out.append(i).append(": ").append(sentences.get(i)).append('\n');
        return out.toString();
    }
}
