package com.lyreo.lexicon;
import static org.assertj.core.api.Assertions.*;
import com.lyreo.lexicon.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PronunciationGroupsTest {
    LexiconEntry.Pronunciation sound(UUID item,String ipa,String record,String region,String usage) {
        var context=new HashMap<String,Object>();context.put("regions",List.of(region));context.put("form","read");context.put("notation_system","IPA");context.put("source_record_key",record);
        context.put("scope_status",usage==null?"UNSPECIFIED":"EXPLICIT");if(usage!=null) context.put("usage",usage);
        return new LexiconEntry.Pronunciation(UUID.randomUUID(),item,region,ipa,record+".ogg","https://example.test/"+record,null,null,"[]",null,context);
    }
    @Test void keepsUsageAndRegionsAndDoesNotAttachAudioByAccentOrPartOfSpeech() {
        var noun=UUID.randomUUID();var verb=UUID.randomUUID();
        var present=sound(noun,"/riːd/","present","UK","present tense");
        var past=sound(verb,"/rɛd/","past","UK","past tense");
        var audioOnly=sound(noun,null,"unknown","UK",null);
        assertThat(PronunciationGroups.from("read",List.of(present,past,audioOnly))).hasSize(3);
        var first=sound(noun,"/liːd/","lead","AU",null);
        var repeated=sound(verb,"/liːd/","lead","AU",null);
        var variants=PronunciationGroups.from("lead",List.of(first,repeated,sound(noun,"/lɛd/","metal","US",null)));
        assertThat(variants).hasSize(2);
        assertThat(variants.getFirst().regions()).containsExactly("AU");
        assertThat(variants.getFirst().scopeStatus()).isEqualTo("UNSPECIFIED");
        assertThat(variants.getFirst().recordings()).hasSize(1);
    }
    @Test void sourceRespellingAndExplicitScopesStayDistinct() {
        var item=UUID.randomUUID();var source=sound(item,"rĕkʹərd","source","US",null);
        var context=new HashMap<>(source.pronunciationContext());context.put("notation_system","SOURCE");
        var respelling=new LexiconEntry.Pronunciation(source.id(),item,source.accent(),source.ipa(),source.audioFile(),source.audioUrl(),null,null,"[]",null,context);
        var groups=PronunciationGroups.from("record",List.of(respelling,sound(item,"/ˈrɛkɔːd/","noun","UK","noun"),sound(item,"/rɪˈkɔːd/","verb","UK","verb")));
        assertThat(groups).hasSize(3);assertThat(groups.getFirst().notationSystem()).isEqualTo("SOURCE");
    }
    @Test void recoversRegionsFromLegacySourceLabels() {
        var raw = List.of("General-American", "Received_Pronunciation", "miền Nam nước Anh", "Úc", "unusual");
        var sound = new LexiconEntry.Pronunciation(UUID.randomUUID(), null, "unspecified", null, null,
            "https://example.test/record.mp3", null, null, "[]", null, Map.of("labels", raw));
        assertThat(PronunciationGroups.regions(sound)).containsExactly("US", "UK", "Southern-England", "AU");
    }

    @Test void combinesNotationRegionsAndPreservesLabelledAudioWithoutLinkingIt() {
        var item = UUID.randomUUID();
        var uk = sound(item, "/kəmˈpækt/", "uk", "UK", null);
        var us = sound(item, "/kəmˈpækt/", "us", "US", null);
        var wav = new LexiconEntry.Pronunciation(UUID.randomUUID(), item, "unspecified", null,
            "compact (adjective).wav", "https://example.test/compact.mp3", null, null, "[]", null,
            Map.of("source", "enwiktionary", "labels", List.of("Southern-England")));

        var variants = PronunciationGroups.from("compact", List.of(uk, us, wav), Map.of(item, "adj"));
        assertThat(variants).hasSize(2);
        assertThat(variants.getFirst().regions()).containsExactly("UK", "US");
        assertThat(variants.getFirst().recordings()).doesNotContain(wav);
        assertThat(variants.getLast().notation()).isNull();
        assertThat(variants.getLast().partOfSpeech()).isEqualTo("adj");
        assertThat(variants.getLast().regions()).containsExactly("Southern-England");
    }
}
