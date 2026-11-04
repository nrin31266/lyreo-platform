package com.lyreo.notification.api;

import com.lyreo.notification.application.port.JobProgressFeed;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final java.util.concurrent.ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
        runnable -> { var thread=new Thread(runnable,"admin-sse-heartbeat");thread.setDaemon(true);return thread; });
    public AdminRealtimeController(JobProgressFeed feed) { this.feed=feed; }
    @PreDestroy public void close() { heartbeat.shutdownNow(); }

    @GetMapping("/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public SseEmitter jobs() {
        SseEmitter emitter=new SseEmitter(0L);
        AtomicBoolean closed=new AtomicBoolean();
        AtomicReference<JobProgressFeed.Subscription> subscription=new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> pulse=new AtomicReference<>();
        Runnable cleanup=()->{
            if(!closed.compareAndSet(false,true))return;
            var current=subscription.get();if(current!=null)current.close();
            var future=pulse.get();if(future!=null)future.cancel(false);
        };
        subscription.set(feed.subscribe(message->{
            if(closed.get())return;
            try { emitter.send(SseEmitter.event().name("job-progress").data(message)); }
            catch(IOException | IllegalStateException failure) { cleanup.run();emitter.complete(); }
        }));
        if(closed.get())subscription.get().close();
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error->cleanup.run());
        try { emitter.send(SseEmitter.event().name("connected").data(Map.of("transport","SSE"))); }
        catch(IOException failure) { cleanup.run();emitter.completeWithError(failure); }
        pulse.set(heartbeat.scheduleAtFixedRate(()->{
            if(closed.get())return;
            try { emitter.send(SseEmitter.event().comment("heartbeat")); }
            catch(IOException | IllegalStateException failure) { cleanup.run();emitter.complete(); }
        },15,15,TimeUnit.SECONDS));
        if(closed.get())pulse.get().cancel(false);
        return emitter;
    }
}
