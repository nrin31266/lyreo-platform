package com.lyreo.toeic.infrastructure;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PassageSanitizerTest {
    @Test void keepsReadingStructureAndRemovesActiveMarkupAndExternalMedia() {
        var sanitizer=new PassageSanitizer();
        String html=sanitizer.clean("<h2>Schedule</h2><table><tr><th colspan='2'>Monday</th></tr><tr><td>9:00</td><td><strong>Meeting</strong></td></tr></table><ul><li>Bring notes</li></ul><script>alert(1)</script><iframe src='https://invalid.example'></iframe><img src='javascript:alert(1)' onerror='alert(1)'><p onclick='alert(1)' style='position:fixed'>Read <a href='javascript:alert(1)'>this</a></p>");
        assertThat(html).contains("<table>","colspan=\"2\"","<strong>Meeting</strong>","<li>Bring notes</li>","Read this");
        assertThat(html).doesNotContain("<script","<iframe","<img","onclick","onerror","javascript:","style=", "alert(1)");
    }
}
