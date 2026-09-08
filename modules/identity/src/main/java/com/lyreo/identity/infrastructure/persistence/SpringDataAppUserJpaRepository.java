package com.lyreo.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Internal Spring Data repository. Other Lyreo modules must never import it. */
public interface SpringDataAppUserJpaRepository extends JpaRepository<JpaAppUserEntity, UUID> {
    Optional<JpaAppUserEntity> findByKeycloakSubject(String keycloakSubject);
}
