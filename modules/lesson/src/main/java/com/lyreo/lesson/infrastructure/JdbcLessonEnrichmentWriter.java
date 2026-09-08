package com.lyreo.lesson.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lyreo.lesson.application.LessonEnrichmentWriter;
import com.lyreo.lesson.domain.LessonSentence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects capability outputs into queryable Lesson tables.
 *
 * <p>Only normalized fields needed by product queries belong here. Complete provider payloads stay
 * in object storage as immutable debug/audit artifacts.</p>
 */
public class JdbcLessonEnrichmentWriter implements LessonEnrichmentWriter {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLessonEnrichmentWriter(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    @Override
    public void replaceStructuredAnnotations(
        UUID lessonId,
        String annotationType,
        List<LessonSentence> sentences,
        Object structured
    ) {
        jdbc.update(
            "DELETE FROM lesson_annotation "
                + "WHERE lesson_id=:lessonId AND annotation_type=:type",
            Map.of("lessonId", lessonId, "type", annotationType)
        );

        JsonNode root = mapper.valueToTree(structured);
        JsonNode items = root.isArray() ? root : root.path("items");
        if (!items.isArray()) return;

        for (JsonNode item : items) {
            int sentenceIndex = item.path("sentence_index").asInt(-1);
            UUID sentenceId = sentenceIdAt(sentences, sentenceIndex);
            insertAnnotation(lessonId, sentenceId, annotationType, item, "AI");
        }
    }

    @Transactional
    @Override
    public void replaceNlpHints(
        UUID lessonId,
        List<LessonSentence> sentences,
        Map<String, Object> output,
        boolean entityHints,
        boolean dictationHints
    ) {
        if (entityHints) {
            deleteAnnotationType(lessonId, "ENTITY_HINTS");
        }
        if (dictationHints) {
            deleteAnnotationType(lessonId, "DICTATION_HINTS");
        }

        JsonNode perSentence = mapper.valueToTree(output).path("sentence_entities");
        if (!perSentence.isArray()) return;

        for (JsonNode sentenceGroup : perSentence) {
            int sentenceIndex = sentenceGroup.path("sentence_index").asInt(-1);
            UUID sentenceId = sentenceIdAt(sentences, sentenceIndex);
            if (sentenceId == null) continue;

            JsonNode entities = sentenceGroup.path("entities");
            if (!entities.isArray()) continue;

            if (entityHints) {
                insertAnnotation(lessonId, sentenceId, "ENTITY_HINTS", entities, "NLP");
            }
            if (dictationHints) {
                JsonNode hints = dictationHints(entities);
                if (!hints.isEmpty()) {
                    insertAnnotation(
                        lessonId,
                        sentenceId,
                        "DICTATION_HINTS",
                        hints,
                        "NLP"
                    );
                }
            }
        }
    }

    @Transactional
    @Override
    public void replaceAlignment(
        UUID lessonId,
        List<LessonSentence> sentences,
        Map<String, Object> output
    ) {
        for (LessonSentence sentence : sentences) {
            jdbc.update(
                "DELETE FROM lesson_word_timestamp WHERE sentence_id=:sentenceId",
                Map.of("sentenceId", sentence.id())
            );
        }

        JsonNode wordsNode = mapper.valueToTree(output).path("words");
        if (!wordsNode.isArray() || sentences.isEmpty()) return;

        List<JsonNode> words = new ArrayList<>();
        wordsNode.forEach(words::add);

        int globalWordIndex = 0;
        for (int sentenceIndex = 0; sentenceIndex < sentences.size(); sentenceIndex++) {
            LessonSentence sentence = sentences.get(sentenceIndex);
            int remainingSentenceCount = sentences.size() - sentenceIndex - 1;
            int remainingWordCount = words.size() - globalWordIndex;
            int expectedWordCount = Math.max(1, lexicalWordCount(sentence.text()));

            int take = Math.min(
                expectedWordCount,
                Math.max(0, remainingWordCount - remainingSentenceCount)
            );
            if (remainingSentenceCount == 0) {
                take = remainingWordCount;
            }
            if (take <= 0) continue;

            long sentenceStart = asMs(words.get(globalWordIndex), "start_ms", "start_time");
            long sentenceEnd = sentenceStart;

            for (
                int localWordIndex = 0;
                localWordIndex < take && globalWordIndex < words.size();
                localWordIndex++, globalWordIndex++
            ) {
                JsonNode word = words.get(globalWordIndex);
                long start = asMs(word, "start_ms", "start_time");
                long end = asMs(word, "end_ms", "end_time");
                sentenceEnd = Math.max(sentenceEnd, end);
                insertWordTimestamp(sentence.id(), localWordIndex, word, start, end);
            }

            jdbc.update(
                """
                UPDATE lesson_sentence
                   SET audio_start_ms=:start,
                       audio_end_ms=:end
                 WHERE id=:sentenceId
                """,
                Map.of(
                    "start", sentenceStart,
                    "end", sentenceEnd,
                    "sentenceId", sentence.id()
                )
            );
        }
    }

    @Transactional
    @Override
    public void replaceSentenceIpa(
        UUID lessonId,
        List<LessonSentence> sentences,
        Object structured,
        String provider,
        String model,
        String accent
    ) {
        JsonNode root = mapper.valueToTree(structured);
        JsonNode items = root.isArray() ? root : root.path("items");
        if (!items.isArray()) return;

        for (JsonNode item : items) {
            int sentenceIndex = item.path("sentence_index").asInt(-1);
            UUID sentenceId = sentenceIdAt(sentences, sentenceIndex);
            if (sentenceId == null) continue;

            String ipa = item.path("ipa").asText("").strip();
            if (ipa.isBlank()) continue;

            LessonSentence sentence = sentences.get(sentenceIndex);
            String textHash = Integer.toHexString(sentence.text().hashCode());
            jdbc.update(
                """
                INSERT INTO sentence_pronunciation(
                  id,
                  sentence_id,
                  accent,
                  ipa,
                  provider,
                  model,
                  text_hash,
                  generated_at
                ) VALUES (
                  :id,
                  :sentenceId,
                  :accent,
                  :ipa,
                  :provider,
                  :model,
                  :textHash,
                  now()
                )
                ON CONFLICT(sentence_id, accent, text_hash) DO UPDATE SET
                  ipa=excluded.ipa,
                  provider=excluded.provider,
                  model=excluded.model,
                  generated_at=now()
                """,
                new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("sentenceId", sentenceId)
                    .addValue("accent", accent)
                    .addValue("ipa", ipa)
                    .addValue("provider", provider)
                    .addValue("model", model)
                    .addValue("textHash", textHash)
            );
        }
    }

    private void deleteAnnotationType(UUID lessonId, String type) {
        jdbc.update(
            "DELETE FROM lesson_annotation "
                + "WHERE lesson_id=:lessonId AND annotation_type=:type",
            Map.of("lessonId", lessonId, "type", type)
        );
    }

    private JsonNode dictationHints(JsonNode entities) {
        var hints = mapper.createArrayNode();
        for (JsonNode entity : entities) {
            String type = entity.path("type").asText();
            if (switch (type) {
                case "PROPER_NOUN", "PERSON", "GPE", "ORG", "NUMBER", "ACRONYM" -> true;
                default -> false;
            }) {
                hints.add(entity);
            }
        }
        return hints;
    }

    private void insertAnnotation(
        UUID lessonId,
        UUID sentenceId,
        String type,
        JsonNode payload,
        String generatedBy
    ) {
        jdbc.update(
            """
            INSERT INTO lesson_annotation(
              id,
              lesson_id,
              sentence_id,
              annotation_type,
              payload_json,
              generated_by,
              status,
              created_at
            ) VALUES (
              :id,
              :lessonId,
              :sentenceId,
              :type,
              CAST(:payload AS jsonb),
              :generatedBy,
              'AVAILABLE',
              now()
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("lessonId", lessonId)
                .addValue("sentenceId", sentenceId)
                .addValue("type", type)
                .addValue("payload", payload.toString())
                .addValue("generatedBy", generatedBy)
        );
    }

    private void insertWordTimestamp(
        UUID sentenceId,
        int position,
        JsonNode word,
        long start,
        long end
    ) {
        jdbc.update(
            """
            INSERT INTO lesson_word_timestamp(
              id,
              sentence_id,
              position,
              surface_text,
              start_ms,
              end_ms
            ) VALUES (
              :id,
              :sentenceId,
              :position,
              :surfaceText,
              :start,
              :end
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sentenceId", sentenceId)
                .addValue("position", position)
                .addValue("surfaceText", firstText(word, "word", "text"))
                .addValue("start", start)
                .addValue("end", end)
        );
    }

    private static UUID sentenceIdAt(List<LessonSentence> sentences, int index) {
        return index >= 0 && index < sentences.size() ? sentences.get(index).id() : null;
    }

    private static int lexicalWordCount(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.isBlank() ? 0 : trimmed.split("\\s+").length;
    }

    private static String firstText(JsonNode node, String firstField, String fallbackField) {
        String first = node.path(firstField).asText("");
        return first.isBlank() ? node.path(fallbackField).asText("") : first;
    }

    private static long asMs(JsonNode node, String msField, String secondsField) {
        if (node.has(msField)) {
            return Math.max(0L, node.path(msField).asLong());
        }
        return Math.max(0L, Math.round(node.path(secondsField).asDouble(0) * 1000d));
    }
}
