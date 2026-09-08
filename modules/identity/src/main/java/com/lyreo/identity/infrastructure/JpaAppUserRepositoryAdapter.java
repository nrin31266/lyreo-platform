package com.lyreo.identity.infrastructure;

import com.lyreo.identity.application.AppUserRepository;
import com.lyreo.identity.domain.AppUser;
import com.lyreo.identity.infrastructure.persistence.JpaAppUserEntity;
import com.lyreo.identity.infrastructure.persistence.SpringDataAppUserJpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * JPA adapter for the Identity aggregate.
 *
 * <p>This module intentionally demonstrates Lyreo's persistence rule: application/domain
 * depend on a port, while JPA lives in infrastructure. Queue/import/projection workloads
 * may still use JDBC where explicit SQL is a better fit.</p>
 */
public final class JpaAppUserRepositoryAdapter implements AppUserRepository {
    private final SpringDataAppUserJpaRepository repository;

    public JpaAppUserRepositoryAdapter(SpringDataAppUserJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AppUser> findBySubject(String subject) {
        return repository.findByKeycloakSubject(subject).map(JpaAppUserRepositoryAdapter::toDomain);
    }

    @Override
    public AppUser create(String subject, String email) {
        // JIT provisioning can race when a client sends parallel first requests. The unique
        // keycloak_subject constraint is authoritative; if another request wins, read that row.
        var entity = new JpaAppUserEntity(UUID.randomUUID(), subject, blankToNull(email), Instant.now());
        try {
            return toDomain(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException concurrentCreate) {
            return repository.findByKeycloakSubject(subject)
                .map(JpaAppUserRepositoryAdapter::toDomain)
                .orElseThrow(() -> concurrentCreate);
        }
    }

    private static AppUser toDomain(JpaAppUserEntity entity) {
        return new AppUser(
            entity.id(),
            entity.keycloakSubject(),
            entity.emailSnapshot(),
            entity.createdAt()
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
