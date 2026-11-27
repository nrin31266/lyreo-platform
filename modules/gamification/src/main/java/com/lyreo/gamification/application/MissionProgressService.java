package com.lyreo.gamification.application;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.application.port.MissionProgressRepository;
import com.lyreo.gamification.application.port.RewardPolicyConfigRepository;
import com.lyreo.gamification.application.port.XpLedger;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import com.lyreo.gamification.domain.XpTransaction;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Event-driven mission application service.
 *
 * <p>Consumer deduplication, mission progress, XP/level update, and Diamond ledger
 * append execute in one database transaction.</p>
 */
public class MissionProgressService {
    private static final String CONSUMER_NAME = "gamification.mission";

    private final MissionProgressRepository missions;
    private final DiamondLedger ledger;
    private final XpLedger xpLedger;
    private final ConsumerReceiptStore receipts;
    private final RewardPolicyConfigRepository policyConfig;
    private final ObjectMapper mapper;

    public MissionProgressService(MissionProgressRepository missions, DiamondLedger ledger) {
        this(missions, ledger, null, null, null, null);
    }

    public MissionProgressService(
        MissionProgressRepository missions,
        DiamondLedger ledger,
        XpLedger xpLedger,
        ConsumerReceiptStore receipts,
        RewardPolicyConfigRepository policyConfig,
        ObjectMapper mapper
    ) {
        this.missions = missions;
        this.ledger = ledger;
        this.xpLedger = xpLedger;
        this.receipts = receipts;
        this.policyConfig = policyConfig;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Transactional
    public void increment(UUID learnerId, String metric, Instant occurredAt, int amount) {
        increment(null, learnerId, metric, occurredAt, amount);
    }

    @Transactional
    public void increment(UUID factId, UUID learnerId, String metric, Instant occurredAt, int amount) {
        if (amount <= 0 || learnerId == null || metric == null) {
            return;
        }
        if (factId != null && receipts != null && !receipts.tryRecord(CONSUMER_NAME, factId)) {
            return;
        }

        for (MissionProgressRepository.MissionRewardTarget mission : missions.activeRewardTargetsByMetric(metric)) {
            String periodKey = periodKey(mission.recurrence(), occurredAt);
            missions.increment(learnerId, mission.id(), periodKey, mission.target(), amount);
        }
    }

    @Transactional
    public ClaimResult claim(UUID learnerId, UUID missionId) {
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(missionId, "missionId");

        MissionProgressRepository.MissionRewardTarget mission = missions.findActiveRewardTargetById(missionId)
            .orElseThrow(() -> new ResourceNotFoundException("Mission not found"));

        Instant now = Instant.now();
        String periodKey = periodKey(mission.recurrence(), now);
        MissionProgressRepository.MissionProgressStatus status = missions
            .findMissionProgressStatus(learnerId, missionId, periodKey)
            .orElse(null);

        if (status == null || status.completedAt() == null) {
            throw new StateConflictException("Mission is not completed yet");
        }
        if (status.claimedAt() != null) {
            throw new StateConflictException("Mission reward has already been claimed");
        }

        RewardPolicyConfig config = policyConfig != null
            ? policyConfig.current()
            : RewardPolicyConfig.defaults();

        String ruleVersion = (mission.ruleVersion() != null && !mission.ruleVersion().isBlank())
            ? mission.ruleVersion()
            : config.normalizedRuleVersion();
        String snapshotJson = buildSnapshotJson(mission, periodKey, ruleVersion);

        if (!missions.markClaimedIfCompleted(learnerId, mission.id(), periodKey, snapshotJson)) {
            throw new StateConflictException("Mission reward has already been claimed");
        }

        int diamondsAwarded = Math.max(0, mission.diamondReward());
        int xpAwarded = Math.max(0, mission.xpReward());

        if (diamondsAwarded > 0) {
            String key = "mission:" + mission.id() + ":" + learnerId + ":" + periodKey;
            ledger.append(new DiamondTransaction(
                UUID.randomUUID(),
                learnerId,
                DiamondTransaction.Type.MISSION_REWARD,
                diamondsAwarded,
                key,
                "MISSION",
                mission.id(),
                now,
                mission.code() != null ? "Mission claimed: " + mission.code() : "Mission claimed",
                ruleVersion
            ));
        }

        if (xpAwarded > 0 && xpLedger != null) {
            String xpKey = "mission-xp:" + mission.id() + ":" + learnerId + ":" + periodKey;
            xpLedger.append(
                new XpTransaction(
                    UUID.randomUUID(),
                    learnerId,
                    "MISSION_REWARD",
                    xpAwarded,
                    xpKey,
                    ruleVersion,
                    mission.id(),
                    now
                ),
                config
            );
        }

        return new ClaimResult(
            mission.id(),
            mission.code(),
            periodKey,
            xpAwarded,
            diamondsAwarded,
            ledger.balance(learnerId),
            now
        );
    }

    public record ClaimResult(
        UUID missionId,
        String code,
        String periodKey,
        int xpAwarded,
        int diamondsAwarded,
        int diamondBalance,
        Instant claimedAt
    ) {}

    private String buildSnapshotJson(
        MissionProgressRepository.MissionRewardTarget mission,
        String periodKey,
        String ruleVersion
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("xp", mission.xpReward());
        snapshot.put("diamonds", mission.diamondReward());
        snapshot.put("ruleVersion", ruleVersion);
        if (mission.code() != null) {
            snapshot.put("missionCode", mission.code());
        }
        snapshot.put("periodKey", periodKey);
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize mission reward snapshot", ex);
        }
    }

    public static String periodKey(String recurrence, Instant at) {
        LocalDate date = (at == null ? Instant.now() : at)
            .atZone(ZoneOffset.UTC)
            .toLocalDate();
        if ("WEEKLY".equals(recurrence)) {
            var fields = WeekFields.ISO;
            return date.get(fields.weekBasedYear())
                + "-W"
                + String.format("%02d", date.get(fields.weekOfWeekBasedYear()));
        }
        if ("ONE_TIME".equals(recurrence)) {
            return "ONE_TIME";
        }
        return date.toString();
    }
}
