package com.lyreo.identity.application;

import com.lyreo.identity.domain.AppUser;

public final class AppUserProvisioningService {
    private final AppUserRepository repository;

    public AppUserProvisioningService(AppUserRepository repository) {
        this.repository = repository;
    }

    /**
     * JIT-provisions the Lyreo-side user mapping from a validated OIDC subject.
     * Keycloak remains the authentication/role source of truth.
     */
    public ProvisionedUser provision(String subject, String email) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("OIDC subject is required");
        }
        AppUser user = repository.findBySubject(subject)
            .orElseGet(() -> repository.create(subject, email));
        return new ProvisionedUser(
            user.id(), user.keycloakSubject(), user.emailSnapshot(), user.createdAt()
        );
    }
}
