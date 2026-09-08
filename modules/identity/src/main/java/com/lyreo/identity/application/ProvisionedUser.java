package com.lyreo.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Public identity view returned to other modules; the internal AppUser aggregate stays private. */
public record ProvisionedUser(
    UUID id,
    String keycloakSubject,
    String emailSnapshot,
    Instant createdAt
) {}
