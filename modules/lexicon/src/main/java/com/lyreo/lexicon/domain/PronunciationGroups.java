package com.lyreo.lexicon.domain;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PronunciationGroups {
    private PronunciationGroups() {}

    public record Variant(UUID id, String form, String notation, String notationSystem, String usage,
                          String scopeStatus, String partOfSpeech, List<String> regions,
                          List<LexiconEntry.Pronunciation> recordings) {}

    private record Key(String form, String notation, String system, String usage,
                       String scope, String unlinkedRecording) {}

    public static List<Variant> from(String headword, List<LexiconEntry.Pronunciation> sounds) {
        return from(headword, sounds, Map.of());
    }

    public static List<Variant> from(String headword, List<LexiconEntry.Pronunciation> sounds, Map<UUID,String> parts) {
        var groups = new LinkedHashMap<Key,List<LexiconEntry.Pronunciation>>();
        for (var sound : sounds) {
            var context = metadata(sound);
            String notation = sound.ipa() == null || sound.ipa().isBlank() ? null : sound.ipa().strip();
            boolean playable = playable(sound);
            if (notation == null && !playable) continue;
            String part = scope(sound, parts);
            String scope = part != null ? part : "EXPLICIT".equals(context.get("scope_status"))
                ? Objects.toString(sound.itemId(), "") : "";
            // A recording with no IPA of its own cannot establish a link to another record.
            var key = new Key(Objects.toString(context.get("form"), headword), notation,
                Objects.toString(context.get("notation_system"), "SOURCE"),
                Objects.toString(context.get("usage"), null), scope, notation == null ? recordingKey(sound) : null);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sound);
        }
        var result = new ArrayList<Variant>();
        groups.forEach((key, records) -> {
            var regions = new LinkedHashSet<String>();
            var unique = new LinkedHashMap<String,LexiconEntry.Pronunciation>();
            for (var sound : records) {
                regions.addAll(regions(sound));
                if (playable(sound)) unique.putIfAbsent(recordingKey(sound), sound);
            }
            result.add(new Variant(UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)),
                key.form(), key.notation(), key.system(), key.usage(), !key.scope().isEmpty() || key.usage() != null ? "EXPLICIT" : "UNSPECIFIED",
                scope(records.getFirst(), parts), List.copyOf(regions), List.copyOf(unique.values())));
        });
        return List.copyOf(result);
    }

    private static final Map<String, String> SOURCE_REGIONS = Map.of(
        "general american", "US", "received pronunciation", "UK",
        "southern england", "Southern-England", "miền nam nước anh", "Southern-England",
        "northern england", "Northern-England", "scotland", "Scotland", "wales", "Wales",
        "california", "California", "úc", "AU");

    public static List<String> regions(LexiconEntry.Pronunciation sound) {
        var context = metadata(sound);
        var regions = new LinkedHashSet<String>();
        if (context.get("regions") instanceof List<?> values && !values.isEmpty()) {
            values.forEach(value -> regions.add(value.toString()));
        } else if (sound.accent() != null && !sound.accent().equals("unspecified")) {
            regions.addAll(List.of(sound.accent().split("/")));
        }
        if (context.get("labels") instanceof List<?> labels) {
            for (var label : labels) {
                String region = SOURCE_REGIONS.get(label.toString().strip().toLowerCase(Locale.ROOT).replaceAll("[-_\\s]+", " "));
                if (region != null) regions.add(region);
            }
        }
        return List.copyOf(regions);
    }

    private static String scope(LexiconEntry.Pronunciation sound, Map<UUID,String> parts) {
        String part = sound.itemId() == null ? null : parts.get(sound.itemId());
        if (part == null) return null;
        var context = metadata(sound);
        if ("EXPLICIT".equals(context.get("scope_status"))) return part;
        if (!"enwiktionary".equals(context.get("source")) || sound.audioFile() == null) return null;
        String label = switch (part) {
            case "adj" -> "adjective";
            case "adv" -> "adverb";
            default -> part;
        };
        String name = sound.audioFile().toLowerCase(Locale.ROOT).replaceFirst("\\.[a-z0-9]+$", "");
        return name.endsWith(" (" + label + ")") ? part : null;
    }

    private static Map<String,Object> metadata(LexiconEntry.Pronunciation sound) {
        return sound.pronunciationContext() == null ? Map.of() : sound.pronunciationContext();
    }

    private static boolean playable(LexiconEntry.Pronunciation sound) {
        return sound.assetId() != null || sound.audioUrl() != null && !sound.audioUrl().isBlank();
    }

    private static String recordingKey(LexiconEntry.Pronunciation sound) {
        return sound.assetId() == null
            ? Objects.toString(sound.audioUrl(), sound.id().toString()) : "asset:" + sound.assetId();
    }
}
