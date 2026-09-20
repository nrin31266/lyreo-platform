package com.lyreo.notification.application.port;

import java.util.UUID;

public interface RealtimeNotificationPort {
    void jobProgress(UUID jobId, String status, String step, int progress);
}
