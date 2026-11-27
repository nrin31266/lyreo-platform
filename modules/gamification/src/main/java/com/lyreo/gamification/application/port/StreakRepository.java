package com.lyreo.gamification.application.port;

import com.lyreo.gamification.domain.LearnerStreak;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface StreakRepository {
    void lockLearner(UUID learnerId);

    boolean recordStudyDate(UUID learnerId, LocalDate studyDate, UUID firstFactId);

    List<LocalDate> listStudyDates(UUID learnerId);

    LearnerStreak upsertStreak(UUID learnerId, int currentStreak, int longestStreak, LocalDate lastStudyDate);

    LearnerStreak findStreak(UUID learnerId);
}
