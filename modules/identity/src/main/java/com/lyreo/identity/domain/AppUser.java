package com.lyreo.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record AppUser(UUID id, String keycloakSubject, String emailSnapshot, Instant createdAt) {}
