package com.lyreo.identity.application.port;

import java.util.Optional;
import java.util.UUID;

public interface LearnerAccountProvider {
    boolean configured();
    Optional<Connection> connect(UUID subject);

    /** Connect only to a verified, manageable learner. */
    interface Connection {
        boolean enabled();
        void enable(boolean enabled);
        void endSessions();
        void resetPassword(String password, boolean changeOnNextLogin);
    }
}
