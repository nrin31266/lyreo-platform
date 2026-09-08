package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.lesson.domain.LessonActivityType;
import com.lyreo.lesson.domain.LessonAnnotationType;
import com.lyreo.lesson.domain.LessonBuildOptions;
import com.lyreo.lesson.domain.LessonProcessingPolicy;
import com.lyreo.lesson.domain.LessonSourceType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LessonProcessingPolicyTest {

    @Test
    void adminPolicyMayDisableAnOtherwiseValidCreatorOption() {
        var policy = new LessonProcessingPolicy(
            Set.of(LessonActivityType.DICTATION),
            Set.of(LessonAnnotationType.TRANSLATION),
            Set.of("DISABLED", "ON_DEMAND"),
            Set.of("US")
        );

        var requested = new LessonBuildOptions(
            LessonSourceType.TEXT,
            Set.of(LessonActivityType.SHADOWING),
            Set.of(LessonAnnotationType.TRANSLATION),
            "US",
            "ON_DEMAND"
        );

        assertThatThrownBy(() -> policy.validate(requested))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("activity disabled");
    }
}
