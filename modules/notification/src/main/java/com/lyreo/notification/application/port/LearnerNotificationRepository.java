package com.lyreo.notification.application.port;

import com.lyreo.notification.domain.LearnerNotification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LearnerNotificationRepository {
    List<LearnerNotification> listAll();

    List<LearnerNotification> listPublishedForAudience(String learnerTier);

    Optional<LearnerNotification> findById(UUID id);

    LearnerNotification insert(LearnerNotification item);

    Optional<LearnerNotification> update(LearnerNotification item);

    boolean delete(UUID id);
}
