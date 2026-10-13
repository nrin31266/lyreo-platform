package com.lyreo.lexicon.api;

import com.lyreo.lexicon.domain.LexiconEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Decodes canonical JSON at the HTTP boundary without coercing or repairing content. */
final class LexiconDetailMapper {
    private final ObjectReader json;

    LexiconDetailMapper(ObjectMapper objectMapper) {
        json = objectMapper.reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    LexiconController.LexiconEntryResponse from(LexiconEntry entry) {
        return new LexiconController.LexiconEntryResponse(entry.id(), entry.headwordId(),
            entry.canonicalForm(), entry.normalizedForm(), entry.type().name(), entry.language(),
            entry.sourceMetadataJson(), entry.licenseText(), entry.items(),
            entry.forms().stream().map(f -> new LexiconController.FormResponse(f.id(), f.itemId(),
                f.form(), f.normalizedForm(), strings(f.tagsJson(), "form.tags"))).toList(),
            entry.senses().stream().map(s -> new LexiconController.SenseResponse(s.id(), s.itemId(),
                s.position(), s.partOfSpeech(), s.definitionEn(), s.translationVi(),
                s.translationStatus().name(), strings(s.rawGlossesJson(), "sense.rawGlosses"),
                examples(s.examplesJson()), strings(s.tagsJson(), "sense.tags"),
                s.matchedQualifier())).toList(),
            entry.pronunciations().stream().map(p -> new LexiconController.PronunciationResponse(
                p.id(), p.itemId(), p.accent(), p.ipa(), p.audioFile(), p.audioUrl(), p.sourceUrl(),
                strings(p.tagsJson(), "pronunciation.tags"))).toList(),
            entry.translations().stream().map(t -> new LexiconController.TranslationResponse(
                t.id(), t.itemId(), t.senseId(), t.wordVi(), t.source(), t.sourceScope(),
                t.sourceSenseQualifier(), t.linkStatus(), t.unlinkedReason(),
                strings(t.tagsJson(), "translation.tags"), t.sourcePos())).toList());
    }

    private List<String> strings(String value, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode element : array(value, field)) {
            if (!element.isString()) throw invalid(field);
            result.add(element.asString());
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> examples(String value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode element : array(value, "sense.examples")) {
            if (!element.isObject()) throw invalid("sense.examples");
            // Retain every source property, including nulls and nested JSON, without a fixed example schema.
            Map<String, Object> properties = new LinkedHashMap<>();
            element.properties().forEach(p -> properties.put(p.getKey(), p.getValue()));
            result.add(properties);
        }
        return List.copyOf(result);
    }

    private JsonNode array(String value, String field) {
        if (value == null) throw invalid(field);
        try {
            JsonNode node = json.readTree(value);
            if (node == null || !node.isArray()) throw invalid(field);
            return node;
        } catch (JacksonException malformed) {
            throw new IllegalStateException("Invalid canonical Lexicon JSON: " + field, malformed);
        }
    }

    private static IllegalStateException invalid(String field) {
        return new IllegalStateException("Invalid canonical Lexicon JSON shape: " + field);
    }
}
