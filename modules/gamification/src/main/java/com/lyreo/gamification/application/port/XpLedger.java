package com.lyreo.gamification.application.port;

import com.lyreo.gamification.domain.LearnerLevel;
import com.lyreo.gamification.domain.XpTransaction;
import java.util.UUID;

public interface XpLedger {
    XpTransaction append(XpTransaction transaction, int xpPerLevel);

    LearnerLevel findLevel(UUID learnerId);
}
