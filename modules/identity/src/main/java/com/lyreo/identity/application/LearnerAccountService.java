package com.lyreo.identity.application;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.ServiceUnavailableException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.identity.application.port.LearnerAccountProvider;
import com.lyreo.identity.application.port.LearnerAccountProvider.Connection;
import com.lyreo.identity.application.port.LearnerAccountStore;
import com.lyreo.identity.application.port.LearnerAccountStore.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LearnerAccountService {
    private final LearnerAccountStore store;
    private final LearnerAccountProvider provider;

    public LearnerAccountService(LearnerAccountStore store, LearnerAccountProvider provider) {
        this.store = store;
        this.provider = provider;
    }

    public Status status(UUID learnerId, String actor) {
        Account account = find(learnerId);
        if (!provider.configured()) return new Status("UNCONFIGURED", account.locked(), account.pending());
        var connection = connect(account, actor);
        return connection.map(remote -> new Status("AVAILABLE", account.locked() || !remote.enabled(), account.pending()))
            .orElseGet(() -> new Status("UNMANAGED", account.locked(), account.pending()));
    }

    public void resetPassword(UUID learnerId, String actor, String password, boolean changeOnNextLogin) {
        store.serialized(learnerId, () -> {
            Account account = find(learnerId);
            Connection remote = requireConnection(account, actor);
            if (account.locked() || !remote.enabled()) {
                throw new StateConflictException("Unlock the learner account before resetting its password");
            }
            remote.resetPassword(password, changeOnNextLogin);
            store.audit(learnerId, actor, changeOnNextLogin ? "TEMPORARY_PASSWORD_SET" : "PASSWORD_SET");
            return null;
        });
    }

    public Status setLocked(UUID learnerId, String actor, boolean locked, String reason) {
        return store.serialized(learnerId, () -> {
            Account account = find(learnerId);
            Connection remote = requireConnection(account, actor);

            if (!account.pending() && account.locked() == locked && remote.enabled() != locked) {
                return new Status("AVAILABLE", locked, false);
            }

            store.change(learnerId, true, true, actor, locked ? "LOCK_REQUESTED" : "UNLOCK_REQUESTED", reason);
            try {
                if (locked) {
                    remote.enable(false);
                    remote.endSessions();
                } else {
                    remote.endSessions();
                    remote.enable(true);
                }
            } catch (ServiceUnavailableException failure) {
                return new Status("AVAILABLE", true, true);
            }

            store.change(learnerId, locked, false, actor, locked ? "LOCKED" : "UNLOCKED", reason);
            return new Status("AVAILABLE", locked, false);
        });
    }

    private Account find(UUID id) {
        return store.find(id).orElseThrow(() -> new ResourceNotFoundException("Learner account not found"));
    }

    private Optional<Connection> connect(Account account, String actor) {
        if (account.subject().equals(actor)) return Optional.empty();
        UUID subject;
        try {
            subject = UUID.fromString(account.subject());
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        return provider.connect(subject);
    }

    private Connection requireConnection(Account account, String actor) {
        if (!provider.configured()) throw new ServiceUnavailableException(
            "ACCOUNT_MANAGEMENT_UNCONFIGURED", "Keycloak account management is not configured", null);
        return connect(account, actor)
            .orElseThrow(() -> new StateConflictException("This account cannot be managed as a learner"));
    }

    public record Status(String management, boolean locked, boolean syncPending) {}
}
