package com.lyreo.lesson.infrastructure.integration;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.application.port.YoutubeChannelPort;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

@Component
public class YoutubeChannelSync implements YoutubeChannelPort, JobHandler {
  private final NamedParameterJdbcTemplate jdbc;
  private final BackgroundJobService jobs;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final String apiKey;
  private final RestClient client;

  public YoutubeChannelSync(
      NamedParameterJdbcTemplate jdbc,
      BackgroundJobService jobs,
      PlatformTransactionManager manager,
      ObjectMapper mapper,
      @Value("${lyreo.lesson.youtube.data-api-key:}") String apiKey) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.transactions = new TransactionTemplate(manager);
    this.mapper = mapper;
    this.apiKey = apiKey;
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    factory.setReadTimeout(Duration.ofSeconds(20));
    client =
        RestClient.builder()
            .baseUrl("https://www.googleapis.com/youtube/v3")
            .requestFactory(factory)
            .build();
  }

  @Override
  public String jobType() {
    return "YOUTUBE_CHANNEL_SYNC";
  }

  @Override
  public UUID synchronize(List<String> ids) {
    if (apiKey.isBlank())
      throw new StateConflictException(
          "YouTube metadata synchronization is not configured on the server");
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 50
        || ids.stream().anyMatch(id -> id == null || !id.matches("UC[A-Za-z0-9_-]{22}"))
        || new HashSet<>(ids).size() != ids.size())
      throw new RequestValidationException("Choose 1–50 distinct YouTube channel IDs");
    return transactions.execute(
        tx -> {
          var args = new MapSqlParameterSource("ids", ids.stream().sorted().toList());
          var channels =
              jdbc.query(
                  "SELECT channel_id,sync_job_id FROM lesson_youtube_channel WHERE channel_id IN"
                      + " (:ids) ORDER BY channel_id FOR UPDATE",
                  args,
                  (rs, n) -> rs.getObject("sync_job_id", UUID.class));
          if (channels.size() != ids.size())
            throw new RequestValidationException("Unknown source channel");
          if (channels.stream()
              .filter(Objects::nonNull)
              .map(jobs::findById)
              .flatMap(Optional::stream)
              .anyMatch(
                  job ->
                      Set.of("QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED")
                          .contains(job.status().name())))
            throw new StateConflictException("A selected channel is already synchronizing");
          UUID job = jobs.enqueue(jobType(), "lesson", UUID.randomUUID(), 25, 3, "{}");
          jdbc.update(
              "UPDATE lesson_youtube_channel SET"
                  + " sync_job_id=:job,sync_completed=false,sync_error=NULL WHERE channel_id IN"
                  + " (:ids)",
              args.addValue("job", job));
          return job;
        });
  }

  @Override
  public void execute(BackgroundJob job, JobExecutionContext context) throws Exception {
    var pending =
        jdbc.query(
            "SELECT channel_id FROM lesson_youtube_channel WHERE sync_job_id=:job AND NOT"
                + " sync_completed ORDER BY channel_id",
            Map.of("job", job.id()),
            (rs, n) -> rs.getString(1));
    if (pending.isEmpty()) return;
    context.throwIfCancellationRequested();
    context.progress("SYNCING_CHANNEL_METADATA", 0);
    String response;
    try {
      response =
          client
              .get()
              .uri(
                  uri ->
                      uri.path("/channels")
                          .queryParam("part", "snippet")
                          .queryParam("id", String.join(",", pending))
                          .build())
              .header("X-Goog-Api-Key", apiKey)
              .retrieve()
              .body(String.class);
    } catch (RestClientResponseException failure) {
      String safe = "YouTube channel metadata is temporarily unavailable";
      if (failure.getStatusCode().value() == 401 || failure.getStatusCode().value() == 400)
        throw new PermanentJobException(
            "YOUTUBE_CHANNEL_CONFIG", "Check the server YouTube metadata configuration");
      boolean quota = failure.getStatusCode().value() == 429;
      if (failure.getStatusCode().value() == 403) {
        try {
          var errors =
              mapper.readTree(failure.getResponseBodyAsString()).path("error").path("errors");
          for (var error : errors)
            if (Set.of(
                    "quotaExceeded",
                    "dailyLimitExceeded",
                    "rateLimitExceeded",
                    "userRateLimitExceeded")
                .contains(error.path("reason").asText())) quota = true;
        } catch (tools.jackson.core.JacksonException invalidResponse) {
          quota = false;
        }
        if (!quota)
          throw new PermanentJobException(
              "YOUTUBE_CHANNEL_CONFIG", "Check the server YouTube metadata permissions");
      }
      if (quota) {
        Instant retryAt = Instant.now().plusSeconds(3600);
        String retry =
            failure.getResponseHeaders() == null
                ? null
                : failure.getResponseHeaders().getFirst("Retry-After");
        if (retry != null) {
          try {
            retryAt =
                Instant.now().plusSeconds(Math.max(1, Math.min(86400, Long.parseLong(retry))));
          } catch (NumberFormatException ignored) {
            try {
              retryAt =
                  java.time.ZonedDateTime.parse(
                          retry, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                      .toInstant();
            } catch (java.time.format.DateTimeParseException invalidDate) {
              retryAt = Instant.now().plusSeconds(3600);
            }
          }
        }
        throw new DeferredJobException(
            "YouTube metadata quota is unavailable; retry later", retryAt);
      }
      throw new IllegalStateException(safe);
    }
    var items = mapper.readTree(response).path("items");
    var found = new HashSet<String>();
    for (var item : items) {
      String id = item.path("id").asText();
      if (!pending.contains(id)) continue;
      var snippet = item.path("snippet");
      String title = snippet.path("title").asText("").strip();
      String avatar = snippet.path("thumbnails").path("high").path("url").asText("");
      if (avatar.isBlank())
        avatar = snippet.path("thumbnails").path("default").path("url").asText("");
      if (title.isBlank()
          || title.length() > 500
          || !(avatar.startsWith("https://") || avatar.isBlank())) continue;
      context.throwIfCancellationRequested();
      context.heartbeat();
      String image = avatar;
      transactions.executeWithoutResult(
          tx -> {
            jobs.fenceForCommit(job.id(), job.leaseOwner());
            jdbc.update(
                """
                UPDATE lesson_youtube_channel SET title=:title,avatar_url=COALESCE(NULLIF(:avatar,''),avatar_url),
                synced_at=now(),sync_error=NULL,sync_completed=true WHERE channel_id=:id AND sync_job_id=:job
                """,
                Map.of("id", id, "title", title, "avatar", image, "job", job.id()));
          });
      found.add(id);
    }
    transactions.executeWithoutResult(
        tx -> {
          jobs.fenceForCommit(job.id(), job.leaseOwner());
          for (String id : pending)
            if (!found.contains(id))
              jdbc.update(
                  "UPDATE lesson_youtube_channel SET sync_error='Channel metadata was not"
                      + " returned',sync_completed=true WHERE channel_id=:id AND sync_job_id=:job",
                  Map.of("id", id, "job", job.id()));
        });
  }
}
