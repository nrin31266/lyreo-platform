package com.lyreo.lexicon.domain;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Only records that themselves supply a notation can establish a shared variant. */
public final class PronunciationGroups {
    private PronunciationGroups() {}
    public record Variant(UUID id,String form,String notation,String notationSystem,String usage,String scopeStatus,List<String> regions,List<LexiconEntry.Pronunciation> recordings) {}
    public static List<Variant> from(String headword,List<LexiconEntry.Pronunciation> sounds) {
        var groups=new LinkedHashMap<String,List<LexiconEntry.Pronunciation>>();
        for(var sound:sounds) {
            var c=sound.pronunciationContext()==null?Map.<String,Object>of():sound.pronunciationContext();
            String usage=Objects.toString(c.get("usage"),"");
            String scope=Objects.toString(c.get("scope_status"),"UNSPECIFIED");
            String record=Objects.toString(c.get("source_record_key"),sound.id().toString());
            String key=Objects.toString(c.get("form"),headword)+"|"+Objects.toString(sound.ipa(),"record:"+record)+"|"+Objects.toString(c.get("notation_system"),"SOURCE")+"|"+usage+"|"+scope+"|"+(scope.equals("EXPLICIT")?sound.itemId():"")+"|"+c.get("regions");
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(sound);
        }
        var result=new ArrayList<Variant>();
        groups.forEach((key,records)->{
            var first=records.getFirst();var c=first.pronunciationContext()==null?Map.<String,Object>of():first.pronunciationContext();
            var unique=new LinkedHashMap<String,LexiconEntry.Pronunciation>();
            for(var sound:records) {
                var metadata=sound.pronunciationContext()==null?Map.<String,Object>of():sound.pronunciationContext();
                String recording=sound.assetId()!=null?"asset:"+sound.assetId():Objects.toString(metadata.get("source_record_key"),sound.id().toString());
                unique.putIfAbsent(recording,sound);
            }
            List<String> regions=c.get("regions") instanceof List<?> values?values.stream().map(Object::toString).toList():List.of();
            result.add(new Variant(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),Objects.toString(c.get("form"),headword),first.ipa(),Objects.toString(c.get("notation_system"),"SOURCE"),Objects.toString(c.get("usage"),null),Objects.toString(c.get("scope_status"),"UNSPECIFIED"),regions,List.copyOf(unique.values())));
        });return List.copyOf(result);
    }
}
