package com.lyreo.notification.api;

import com.lyreo.notification.application.JobProgressFeed;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/admin/realtime")
public class AdminRealtimeController {
    private final JobProgressFeed feed;

    public AdminRealtimeController(JobProgressFeed feed) {
        this.feed = feed;
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public SseEmitter jobs() {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<JobProgressFeed.Subscription> subscriptionRef = new AtomicReference<>();

        JobProgressFeed.Subscription subscription = feed.subscribe(message -> {
            try {
                emitter.send(SseEmitter.event().name("job-progress").data(message));
            } catch (IOException | IllegalStateException failure) {
                JobProgressFeed.Subscription current = subscriptionRef.get();
                if (current != null) current.close();
            }
        });
        subscriptionRef.set(subscription);

        Runnable cleanup = subscription::close;
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("transport", "SSE")));
        } catch (IOException failure) {
            cleanup.run();
            emitter.completeWithError(failure);
        }
        return emitter;
    }
}
