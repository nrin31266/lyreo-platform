package com.lyreo.gamification.application;

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

        RewardPolicyConfig config = policyConfig != null
            ? policyConfig.current()
            : RewardPolicyConfig.defaults();

        for (MissionProgressRepository.MissionRewardTarget mission : missions.activeRewardTargetsByMetric(metric)) {
            String periodKey = periodKey(mission.recurrence(), occurredAt);
            missions.increment(learnerId, mission.id(), periodKey, mission.target(), amount);
            autoClaimIfCompleted(learnerId, mission, periodKey, config);
        }
    }

    private void autoClaimIfCompleted(
        UUID learnerId,
        MissionProgressRepository.MissionRewardTarget mission,
        String periodKey,
        RewardPolicyConfig config
    ) {
        if (mission.diamondReward() <= 0 && mission.xpReward() <= 0) {
            return;
        }

        String ruleVersion = (mission.ruleVersion() != null && !mission.ruleVersion().isBlank())
            ? mission.ruleVersion()
            : config.normalizedRuleVersion();
        String snapshotJson = buildSnapshotJson(mission, periodKey, ruleVersion);

        if (!missions.markClaimedIfCompleted(learnerId, mission.id(), periodKey, snapshotJson)) {
            return;
        }

        Instant now = Instant.now();
        if (mission.diamondReward() > 0) {
            String key = "mission:" + mission.id() + ":" + learnerId + ":" + periodKey;
            ledger.append(new DiamondTransaction(
                UUID.randomUUID(),
                learnerId,
                DiamondTransaction.Type.MISSION_REWARD,
                mission.diamondReward(),
                key,
                "MISSION",
                mission.id(),
                now,
                mission.code() != null ? "Mission completed: " + mission.code() : "Mission completed",
                ruleVersion
            ));
        }

        if (mission.xpReward() > 0 && xpLedger != null) {
            String xpKey = "mission-xp:" + mission.id() + ":" + learnerId + ":" + periodKey;
            xpLedger.append(
                new XpTransaction(
                    UUID.randomUUID(),
                    learnerId,
                    "MISSION_REWARD",
                    mission.xpReward(),
                    xpKey,
                    ruleVersion,
                    mission.id(),
                    now
                ),
                config
            );
        }
    }

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
