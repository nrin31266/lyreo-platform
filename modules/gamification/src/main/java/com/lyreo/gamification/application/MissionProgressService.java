package com.lyreo.gamification.application;

import com.lyreo.gamification.domain.DiamondTransaction;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event-driven mission application service.
 *
 * <p>The mission progress row and Diamond ledger append execute in one database
 * transaction. That avoids the old failure mode where a mission could be marked
 * claimed even if the reward write failed.</p>
 */
public class MissionProgressService {
    private final MissionProgressRepository missions;
    private final DiamondLedger ledger;

    public MissionProgressService(MissionProgressRepository missions, DiamondLedger ledger) {
        this.missions = missions;
        this.ledger = ledger;
    }

    @Transactional
    public void increment(UUID learnerId, String metric, Instant occurredAt, int amount) {
        if (amount <= 0) return;

        for (MissionProgressRepository.MissionTarget mission : missions.activeByMetric(metric)) {
            String periodKey = periodKey(mission.recurrence(), occurredAt);
            missions.increment(learnerId, mission.id(), periodKey, mission.target(), amount);
            autoClaimIfCompleted(learnerId, mission, periodKey);
        }
    }

    private void autoClaimIfCompleted(
        UUID learnerId,
        MissionProgressRepository.MissionTarget mission,
        String periodKey
    ) {
        if (mission.diamondReward() <= 0) return;
        if (!missions.markClaimedIfCompleted(learnerId, mission.id(), periodKey)) return;

        String key = "mission:" + mission.id() + ":" + learnerId + ":" + periodKey;
        ledger.append(new DiamondTransaction(
            UUID.randomUUID(),
            learnerId,
            DiamondTransaction.Type.MISSION_REWARD,
            mission.diamondReward(),
            key,
            "MISSION",
            mission.id(),
            Instant.now()
        ));
    }

    private static String periodKey(String recurrence, Instant at) {
        LocalDate date = (at == null ? Instant.now() : at)
            .atZone(ZoneOffset.UTC)
            .toLocalDate();
        if ("WEEKLY".equals(recurrence)) {
            var fields = WeekFields.ISO;
            return date.get(fields.weekBasedYear())
                + "-W"
                + String.format("%02d", date.get(fields.weekOfWeekBasedYear()));
        }
        if ("ONE_TIME".equals(recurrence)) return "ONE_TIME";
        return date.toString();
    }
}
