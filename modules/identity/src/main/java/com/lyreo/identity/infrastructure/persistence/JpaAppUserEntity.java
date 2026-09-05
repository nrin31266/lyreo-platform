package com.lyreo.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistence-only representation of {@code app_user}.
 *
 * <p>The domain model stays free of JPA annotations. Flyway owns the physical table;
 * Hibernate only maps/validates it at runtime.</p>
 */
@Entity
@Table(name = "app_user")
public class JpaAppUserEntity {

    @Id
    private UUID id;

    @Column(name = "keycloak_subject", nullable = false, unique = true, updatable = false)
    private String keycloakSubject;

    @Column(name = "email_snapshot")
    private String emailSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected JpaAppUserEntity() {
        // Required by JPA. Domain code never constructs this type directly.
    }

    public JpaAppUserEntity(UUID id, String keycloakSubject, String emailSnapshot, Instant createdAt) {
        this.id = id;
        this.keycloakSubject = keycloakSubject;
        this.emailSnapshot = emailSnapshot;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String keycloakSubject() {
        return keycloakSubject;
    }

    public String emailSnapshot() {
        return emailSnapshot;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
