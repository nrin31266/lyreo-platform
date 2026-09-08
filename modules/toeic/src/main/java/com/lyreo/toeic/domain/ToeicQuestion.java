package com.lyreo.toeic.domain;

import java.util.List;
import java.util.UUID;

public record ToeicQuestion(
    UUID id,
    UUID testId,
    Integer part,
    Integer questionNumber,
    UUID passageId,
    String questionText,
    List<Option> options,
    String correctAnswer,
    Integer difficultyLevel,
    String audioObjectKey,
    String imageObjectKey
) {
    public record Option(String key, String text) {}
}
