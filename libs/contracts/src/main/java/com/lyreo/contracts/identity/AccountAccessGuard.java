package com.lyreo.contracts.identity;

import java.time.Instant;

/** Applies account state to an otherwise valid bearer token. */
public interface AccountAccessGuard {
    boolean canUseToken(String subject, Instant issuedAt);
}
