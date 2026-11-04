package com.lyreo.lesson.domain.authoring;

import java.util.List;
import java.util.UUID;

/** Mutable draft data; only the authoring transaction writes it. Published rows are immutable. */
public final class AuthoringSentence {
    public String timingSource;
    public UUID id;
    public String text;
    public String usage = "LEARN";
    public List<Word> words = List.of();
    // Retain observed source evidence only while editable words are stale.
    public List<AuthoringText.ObservedWord> sourceWords = List.of();
    public List<String> issues = List.of();
    public Long coarseStartMs;
    public Long coarseEndMs;
    public String translation;
    public List<Annotation> vocabulary = List.of();
    public List<Annotation> grammar = List.of();
    public String dictationPriority;
    public String shadowingPriority;
    public boolean dictationEligible;
    public boolean shadowingEligible;
    public boolean dictationSelected;
    public boolean shadowingSelected;
    public List<Hint> hints = List.of();
    public List<Note> notes = List.of();
    public List<Group> thoughtGroups = List.of();

    public AuthoringSentence() {}
    public AuthoringSentence(UUID id, String text, String usage) {
        this.id = id;
        this.text = text;
        this.usage = usage;
    }
    public void clearSemantic() {
        translation = null;
        vocabulary = List.of();
        grammar = List.of();
        dictationPriority = null;
        shadowingPriority = null;
        dictationSelected = false;
        shadowingSelected = false;
        hints = List.of();
        notes = List.of();
        thoughtGroups = List.of();
    }
    public record Word(String text, long startMs, long endMs, int charStart, int charEnd,
                       int tokenStart, int tokenEnd) {}
    public record Annotation(int tokenStart, int tokenEnd, String surface, String label,
                             String explanation, String importance, String canonicalForm, UUID headwordId) {}
    public record Hint(int tokenStart, int tokenEnd, String text) {}
    public record Note(int tokenStart, int tokenEnd, String text) {}
    public record Group(int tokenStart, int tokenEnd) {}
}
