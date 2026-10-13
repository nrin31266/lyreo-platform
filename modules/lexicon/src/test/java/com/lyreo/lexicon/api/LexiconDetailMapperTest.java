package com.lyreo.lexicon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.lexicon.domain.LexiconEntry;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class LexiconDetailMapperTest {
    private final LexiconDetailMapper mapper = new LexiconDetailMapper(new ObjectMapper());

    static Stream<Arguments> invalidJson() {
        return Stream.of("form.tags", "sense.rawGlosses", "sense.examples", "sense.tags",
                "pronunciation.tags", "translation.tags")
            .flatMap(field -> Stream.of(null, "", "null", "{}", "\"[]\"", "[", "[] []",
                    "[null]", "[1]", field.equals("sense.examples") ? "[\"text\"]" : "[{}]")
                .map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @MethodSource("invalidJson")
    void rejectsMalformedOrWrongShapeInsteadOfRepairing(String field, String value) {
        assertThatThrownBy(() -> mapper.from(entry(field, value)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(field);
    }

    @Test
    void rejectsDuplicateExampleKeysRatherThanDiscardingSourceValues() {
        assertThatThrownBy(() -> mapper.from(entry("sense.examples", "[{\"text\":\"first\",\"text\":\"second\"}]")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("sense.examples");
    }

    @Test
    void preservesOrderedStringsAndEveryExamplePropertyWithoutCoercion() {
        var response = mapper.from(entry("sense.examples", """
            [{"text":"bank \\"quotation\\" — ngân hàng","ref":null,"type":"example",
              "extra":{"values":[false,3,null,{"label":"nested"}],"decimal":1.1234567890123456789}},
             {"text":"second","type":"quotation","ref":"source"}]
            """));
        assertThat(response.translations().getFirst().sourcePos()).isNull();
        assertThat(response.forms().getFirst().tags()).containsExactly("plural", "rare", "plural");
        assertThat(response.senses().getFirst().rawGlosses()).containsExactly("first", "second");
        var examples = new ObjectMapper().valueToTree(response.senses().getFirst().examples());
        assertThat(examples.size()).isEqualTo(2);
        assertThat(examples.get(0).get("text").asString()).isEqualTo("bank \"quotation\" — ngân hàng");
        assertThat(examples.get(0).get("ref").isNull()).isTrue();
        assertThat(examples.get(0).get("extra").get("values").get(3).get("label").asString()).isEqualTo("nested");
        assertThat(examples.get(0).get("extra").get("decimal").decimalValue())
            .isEqualByComparingTo("1.1234567890123456789");
        assertThat(examples.get(1).get("ref").asString()).isEqualTo("source");
    }

    private static LexiconEntry entry(String field, String value) {
        UUID item = UUID.randomUUID();
        UUID sense = UUID.randomUUID();
        return new LexiconEntry(UUID.randomUUID(), UUID.randomUUID(), "bank", "bank",
            LexiconEntry.EntryType.WORD, "en", "{}", "license", List.of(),
            List.of(new LexiconEntry.LexiconForm(UUID.randomUUID(), item, "banks", "banks",
                field.equals("form.tags") ? value : "[\"plural\",\"rare\",\"plural\"]")),
            List.of(new LexiconEntry.LexiconSense(sense, item, 1, "noun", "bank", null,
                LexiconEntry.TranslationStatus.MISSING,
                field.equals("sense.rawGlosses") ? value : "[\"first\",\"second\"]",
                field.equals("sense.examples") ? value : "[]",
                field.equals("sense.tags") ? value : "[]", null)),
            List.of(new LexiconEntry.Pronunciation(UUID.randomUUID(), item, "US", "/bæŋk/",
                null, null, null, "internal/key", field.equals("pronunciation.tags") ? value : "[]")),
            List.of(new LexiconEntry.LexiconTranslation(UUID.randomUUID(), item, sense, "ngân hàng",
                "enwiktionary", "item", "bank", "QUALIFIER_MATCH", null,
                field.equals("translation.tags") ? value : "[]", null)));
    }
}
