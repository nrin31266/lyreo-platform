package com.lyreo.grammar.application;

import com.lyreo.grammar.domain.GrammarQuestion;

public final class GrammarPracticeScorer {
    public boolean correct(GrammarQuestion question, String answer) {
        return question.correctAnswer() != null &&
               question.correctAnswer().equalsIgnoreCase(answer == null ? "" : answer.strip());
    }
}
