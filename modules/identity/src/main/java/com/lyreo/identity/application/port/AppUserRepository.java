package com.lyreo.identity.application.port;

import com.lyreo.identity.domain.AppUser;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository {
    boolean existsById(UUID id);
    Optional<AppUser> findBySubject(String subject);
    AppUser create(String subject, String email);
}
