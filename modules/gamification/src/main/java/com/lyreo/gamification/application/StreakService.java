package com.lyreo.gamification.application;

import com.lyreo.gamification.application.port.StreakRepository;
import com.lyreo.gamification.domain.LearnerStreak;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains UTC daily study dates and consecutive streak counters, including
 * out-of-order/late events and skipped-day resets.
 */
public class StreakService {
    private static final String CONSUMER_NAME = "gamification.streak";

    private final StreakRepository streaks;
    private final ConsumerReceiptStore receipts;

    public StreakService(StreakRepository streaks) {
        this(streaks, null);
    }

    public StreakService(StreakRepository streaks, ConsumerReceiptStore receipts) {
        this.streaks = Objects.requireNonNull(streaks, "streaks");
        this.receipts = receipts;
    }

    @Transactional
    public LearnerStreak recordActivity(UUID factId, UUID learnerId, Instant occurredAt) {
        if (learnerId == null) {
            return null;
        }
        if (factId != null && receipts != null && !receipts.tryRecord(CONSUMER_NAME, factId)) {
            return streaks.findStreak(learnerId);
        }

        Instant timestamp = occurredAt == null ? Instant.now() : occurredAt;
        LocalDate studyDate = timestamp.atZone(ZoneOffset.UTC).toLocalDate();

        streaks.lockLearner(learnerId);
        streaks.recordStudyDate(learnerId, studyDate, factId != null ? factId : UUID.randomUUID());

        List<LocalDate> dates = streaks.listStudyDates(learnerId);
        if (dates.isEmpty()) {
            return LearnerStreak.empty(learnerId);
        }

        int longest = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate date : dates) {
            if (prev != null && prev.plusDays(1).equals(date)) {
                run++;
            } else {
                run = 1;
            }
            if (run > longest) {
                longest = run;
            }
            prev = date;
        }

        LocalDate lastDate = dates.getLast();
        int current = run;
        return streaks.upsertStreak(learnerId, current, longest, lastDate);
    }
}
