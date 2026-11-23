package com.lyreo.lesson.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.lesson.application.build.SourceMaterializationException;
import com.lyreo.lesson.application.port.AuthoringSourcePort.CaptionLine;
import com.lyreo.lesson.application.port.AuthoringSourcePort.CaptionTrack;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class YoutubeEnglishCaptionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void prefersManualEnglishAndAcceptsOriginalAutomaticEnglish() {
        var metadata = mapper.readTree("""
            {"subtitles":{"en-GB":[{"ext":"json3"}],"en":[{"ext":"vtt"}]},
             "automatic_captions":{"en-orig":[{"ext":"json3","url":"https://example.test/captions?lang=en&kind=asr"}]}}
            """);
        assertThat(YoutubeEnglishCaptions.inspect(metadata)).isEqualTo(new CaptionTrack("en-GB", false));
        assertThat(YoutubeEnglishCaptions.inspect(mapper.readTree("""
            {"automatic_captions":{"en-orig":[{"ext":"json3","url":"https://example.test/captions?lang=en&kind=asr"}]}}
            """))).isEqualTo(new CaptionTrack("en-orig", true));
    }

    @Test void rejectsTranslatedAndAmbiguousAutomaticFormats() {
        var metadata = mapper.readTree("""
            {"automatic_captions":{
              "en":[{"ext":"json3","url":"https://example.test/captions?lang=es&tlang=en"}],
              "en-GB":[{"ext":"json3","url":"https://example.test/captions?lang=en&%74lang=en-GB"}],
              "en-orig":[{"ext":"json3","url":"https://example.test/captions?lang=en&lang=en"}],
              "en-US":[{"ext":"json3","url":"https://example.test/captions?lang=en"},
                       {"ext":"json3","url":"https://example.test/captions?lang=es&tlang=en-US"}]}}
            """);
        assertThat(YoutubeEnglishCaptions.inspect(metadata)).isNull();
    }

    @Test void convertsRollingCuesIntoOrderedClipRelativeRanges() {
        var document = mapper.readTree("""
            {"events":[{"tStartMs":2000,"dDurationMs":3000,"segs":[{"utf8":"First"},{"utf8":" line."}]},
                       {"tStartMs":3000,"dDurationMs":2000,"segs":[{"utf8":"Second\\nline."}]},
                       {"tStartMs":5000,"dDurationMs":1000,"segs":[{"utf8":"[Music]"}]},
                       {"tStartMs":6000,"dDurationMs":1000,"segs":[{"utf8":"Outside."}]}]}
            """);
        assertThat(YoutubeEnglishCaptions.lines(document, 2000, 5000)).containsExactly(
            new CaptionLine("First line.", 0, 1000), new CaptionLine("Second line.", 1000, 3000));
        assertThatThrownBy(() -> YoutubeEnglishCaptions.lines(document, 2500, 5000))
            .isInstanceOfSatisfying(SourceMaterializationException.class, error -> {
                assertThat(error.errorCode()).isEqualTo("YOUTUBE_CAPTIONS_UNAVAILABLE");
                assertThat(error.retryable()).isFalse();
            });
    }

    @Test void rejectsFractionalCueTimes() {
        var document = mapper.readTree("""
            {"events":[{"tStartMs":1000.5,"dDurationMs":1000,"segs":[{"utf8":"First line."}]}]}
            """);
        assertThatThrownBy(() -> YoutubeEnglishCaptions.lines(document, 0, 3000))
            .isInstanceOf(SourceMaterializationException.class);
    }

    @Test void skipsSoundCuesWithoutDroppingParenthesizedSpeechOrLyrics() {
        var document = mapper.readTree("""
            {"events":[{"tStartMs":0,"dDurationMs":900,"segs":[{"utf8":"(plane roaring)"}]},
                       {"tStartMs":1000,"dDurationMs":600,"segs":[{"utf8":"(I know.)"}]},
                       {"tStartMs":2000,"dDurationMs":600,"segs":[{"utf8":"[Applause] ♪ Sing with me ♪"}]},
                       {"tStartMs":3000,"dDurationMs":600,"segs":[{"utf8":"♪ [Music] ♪"}]},
                       {"tStartMs":4000,"dDurationMs":600,"segs":[{"utf8":"[I will.]"}]}]}
            """);
        assertThat(YoutubeEnglishCaptions.lines(document, 0, 5000)).containsExactly(
            new CaptionLine("(I know.)", 1000, 1600), new CaptionLine("Sing with me", 2000, 2600),
            new CaptionLine("[I will.]", 4000, 4600));
    }
}
