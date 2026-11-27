package com.lyreo.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminActivity(String id, String category, String action, UUID learnerId, String learnerEmail,
                            String actor, String detail, Integer amount, Instant occurredAt, Instant scheduledFor) {}
