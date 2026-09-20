package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.domain.build.LessonBuildOptions;
import com.lyreo.lesson.domain.build.LessonProcessingPolicy;
import com.lyreo.lesson.domain.content.LessonActivityType;
import com.lyreo.lesson.domain.content.LessonAnnotationType;
import com.lyreo.lesson.domain.content.LessonSourceType;
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
            .isInstanceOf(StateConflictException.class)
            .hasMessageContaining("activity disabled");
    }
}
