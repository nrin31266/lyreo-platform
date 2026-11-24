package com.lyreo.vocabulary.application.port;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public interface VocabularyCatalog {
    int PAGE_SIZE = 30;

    List<Pack> packs();
    List<Unit> units(UUID packId);
    EntryPage entries(UUID packId, UUID unitId, String query, Review review, int page);

    default EntryPage entries(UUID packId, UUID unitId, String query, int page) {
        return entries(packId, unitId, query, Review.ALL, page);
    }
    Entry entry(UUID id);
    void savePack(UUID id, PackEdit edit);
    void saveUnit(UUID id, UnitEdit edit);
    void saveEntry(UUID id, EntryEdit edit);

    enum Review { ALL, NEEDS_REVIEW, MISSING_VI, MISSING_SYNONYMS, MISSING_ANTONYMS, HIDDEN }

    record Pack(UUID id, String titleEn, String titleVi, boolean hidden, long revision, long unitCount, long entryCount, UUID coverImage) {}
    record Unit(UUID id, String title, String level, boolean hidden, long revision, long entryCount) {}
    record EntrySummary(UUID id, UUID packId, UUID unitId, String unitTitle, String level, String term,
                        String pos, String ipa, int senseCount, String definitionEn, String meaningVi,
                        UUID audioId, boolean hidden, boolean needsReview, boolean missingVietnamese) {}
    record EntryPage(List<EntrySummary> items, long total) {}
    record Entry(UUID id, String term, boolean hidden, long revision, LearningContent content, Media media) {}
    record Media(UUID image, UUID ukAudio, UUID usAudio, UUID exampleAudio, UUID secondExampleAudio) {}
    record Sense(@NotBlank @Size(max = 24) String pos, @NotBlank @Size(max = 500) String definitionEn,
                 @Size(max = 500) String meaningVi) {}
    record ContextNote(String pos, String meaningVi, List<UUID> sourceGroupIds) {}
    record LearningContent(
        @NotBlank @Size(max = 120) String ipaUk,
        @NotBlank @Size(max = 120) String ipaUs,
        @NotNull @Size(min = 1, max = 3) List<@NotNull @Valid Sense> senses,
        @NotNull @Size(min = 2, max = 2) List<@NotBlank @Size(max = 500) String> examples,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 200) String> collocations,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 200) String> family,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 200) String> synonyms,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 200) String> antonyms,
        @Size(min = 2, max = 2) List<@Size(max = 500) String> examplesVi,
        @Size(max = 12) List<@Size(max = 500) String> collocationsVi,
        Boolean needsReview,
        List<ContextNote> contextNotes
    ) {}
    record PackEdit(@NotNull @PositiveOrZero Long revision, @NotBlank @Size(max = 120) String titleEn,
                    @NotBlank @Size(max = 120) String titleVi, @NotNull Boolean hidden) {}
    record UnitEdit(@NotNull @PositiveOrZero Long revision, @NotBlank @Size(max = 120) String title,
                    @NotNull Boolean hidden, @Pattern(regexp = "[ABC][12]") String level) {}
    record EntryEdit(@NotNull @PositiveOrZero Long revision, @NotNull @Valid LearningContent content,
                     @NotNull Boolean hidden) {}
}
