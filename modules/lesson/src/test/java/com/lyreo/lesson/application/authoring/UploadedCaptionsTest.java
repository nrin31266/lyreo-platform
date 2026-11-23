package com.lyreo.lesson.application.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lesson.application.port.AuthoringSourcePort.CaptionLine;
import org.junit.jupiter.api.Test;

class UploadedCaptionsTest {
    @Test void webVttUsesVideoClockAndKeepsPlainText() {
        var file=new UploadedCaptions.File("vtt", """
            WEBVTT

            00:01.000 --> 00:02.000
            Outside range.

            NOTE Cue identifiers and styles are optional.

            speech-1
            00:06.000 --> 00:07.300 align:start
            <i>Milk &amp; tea.</i>

            00:08.000 --> 00:09.000
            [music]
            """);
        assertThat(UploadedCaptions.parse(file,5000,10000)).containsExactly(new CaptionLine("Milk & tea.",1000,2300));
    }

    @Test void blocksAnSrtCueCutAtTheSelectedRangeAndRejectsBlankFiles() {
        var file=new UploadedCaptions.File("srt","1\r\n00:00:05,200 --> 00:00:06,500\r\nReview this line.");
        assertThat(UploadedCaptions.parse(file,5000,7000)).containsExactly(new CaptionLine("Review this line.",200,1500));
        assertThatThrownBy(() -> UploadedCaptions.parse(file,6000,7000))
            .isInstanceOf(RequestValidationException.class).hasMessageContaining("cuts through");
        assertThatThrownBy(() -> UploadedCaptions.parse(new UploadedCaptions.File("srt"," \n"),0,1000))
            .isInstanceOf(RequestValidationException.class).hasMessageContaining("no usable");
    }

    @Test void skipsSoundCueCutByClipAndKeepsSpokenText() {
        var file=new UploadedCaptions.File("srt", """
            1
            00:00:00,000 --> 00:00:02,000
            (plane roaring)

            2
            00:00:03,000 --> 00:00:04,000
            &#91;Applause&#93; (Yes, I can.)
            """);
        assertThat(UploadedCaptions.parse(file,1000,5000))
            .containsExactly(new CaptionLine("(Yes, I can.)",2000,3000));
    }
}
