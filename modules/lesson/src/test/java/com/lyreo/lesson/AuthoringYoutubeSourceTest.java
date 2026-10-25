package com.lyreo.lesson;

import com.lyreo.lesson.infrastructure.integration.AuthoringYoutubeSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthoringYoutubeSourceTest {
    @Test void canonicalizesSupportedVideoUrls() {
        for (String url : java.util.List.of("https://youtu.be/Abc_123-xYz?t=20",
            "https://www.youtube.com/watch?v=Abc_123-xYz&list=ignored",
            "https://m.youtube.com/shorts/Abc_123-xYz", "https://youtube.com/embed/Abc_123-xYz")) {
            assertThat(AuthoringYoutubeSource.videoId(url)).isEqualTo("Abc_123-xYz");
        }
    }
    @Test void rejectsUntrustedHostsAndAmbiguousIdentity() {
        for (String url : java.util.List.of("https://youtube.com.evil.test/watch?v=Abc_123-xYz",
            "https://evil.test/https://youtu.be/Abc_123-xYz", "file:///Abc_123-xYz",
            "https://user@youtube.com/watch?v=Abc_123-xYz", "https://youtu.be/Abc_123-xYz/extra",
            "https://youtube.com/watch?v=Abc_123-xYz&v=Xyz_123-aBc")) {
            assertThatThrownBy(() -> AuthoringYoutubeSource.videoId(url))
                .isInstanceOf(com.lyreo.contracts.errors.RequestValidationException.class);
        }
    }
}
