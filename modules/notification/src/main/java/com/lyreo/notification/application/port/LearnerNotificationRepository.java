package com.lyreo.notification.application.port;

import com.lyreo.notification.domain.AdminActivity;
import com.lyreo.notification.domain.LearnerNotification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LearnerNotificationRepository {
    List<LearnerNotification> listAll();

    List<LearnerNotification> listPublishedForAudience(UUID learnerId, String learnerTier);

    default List<LearnerNotification> listPublishedForAudience(
        UUID learnerId,
        String learnerTier,
        boolean unreadOnly,
        int offset,
        int limit
    ) {
        return listPublishedForAudience(learnerId, learnerTier);
    }

    default long countPublishedForAudience(UUID learnerId, String learnerTier, boolean unreadOnly) {
        return listPublishedForAudience(learnerId, learnerTier).size();
    }

    default long countUnreadForAudience(UUID learnerId, String learnerTier) {
        return countPublishedForAudience(learnerId, learnerTier, true);
    }

    default boolean markRead(UUID learnerId, String learnerTier, UUID notificationId) {
        return false;
    }

    default int markAllRead(UUID learnerId, String learnerTier) {
        return 0;
    }

    boolean recipientsExist(List<UUID> recipientIds);

    List<AdminActivity> listActivity(String category, int offset, int limit);

    void audit(LearnerNotification item, String actor, String action);

    Optional<LearnerNotification> findByIdForUpdate(UUID id);

    LearnerNotification insert(LearnerNotification item);

    Optional<LearnerNotification> update(LearnerNotification item);

    boolean delete(UUID id);
}
