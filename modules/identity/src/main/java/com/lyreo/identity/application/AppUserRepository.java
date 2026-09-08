package com.lyreo.identity.application;

import com.lyreo.identity.domain.AppUser;
import java.util.Optional;

public interface AppUserRepository {
    Optional<AppUser> findBySubject(String subject);
    AppUser create(String subject, String email);
}
