package com.lyreo.lesson.api;

import com.lyreo.lesson.application.port.YoutubeChannelPort;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lesson-channels")
@PreAuthorize("hasRole('ADMIN')")
public class YoutubeChannelController {
  private final YoutubeChannelPort channels;

  public YoutubeChannelController(YoutubeChannelPort channels) {
    this.channels = channels;
  }

  @PostMapping("/sync")
  public ResponseEntity<Accepted> sync(@RequestBody Request request) {
    UUID job = channels.synchronize(request.channelIds());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/jobs/" + job))
        .body(new Accepted(job));
  }

  public record Request(List<String> channelIds) {}

  public record Accepted(UUID jobId) {}
}
