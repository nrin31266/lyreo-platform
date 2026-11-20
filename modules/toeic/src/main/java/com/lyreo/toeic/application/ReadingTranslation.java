package com.lyreo.toeic.application;

import com.lyreo.toeic.domain.ToeicDraft;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;

final class ReadingTranslation {
    private static final Pattern INSERTION_MARKER = Pattern.compile("\\[([1-4])\\]");

    private ReadingTranslation() {}

    static boolean hasMisalignedMarkers(ToeicDraft.Group group) {
        if (group.part() != 7 || group.translationVi() == null || group.translationVi().isBlank()) {
            return false;
        }
        var original = group.documents().stream()
            .flatMap(document -> markers(Jsoup.parseBodyFragment(document.html()).text()).stream())
            .toList();
        return !original.isEmpty() && !original.equals(markers(group.translationVi()));
    }

    private static List<String> markers(String text) {
        return INSERTION_MARKER.matcher(text).results().map(result -> result.group(1)).toList();
    }
}
