package com.lyreo.identity.application.port;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface LearnerAccountStore {
    Optional<Account> find(UUID learnerId);
    <T> T serialized(UUID learnerId, Supplier<T> action);
    void change(UUID learnerId, boolean locked, boolean pending, String actor, String action, String reason);
    void audit(UUID learnerId, String actor, String action);

    record Account(String subject, boolean locked, boolean pending) {}
}
