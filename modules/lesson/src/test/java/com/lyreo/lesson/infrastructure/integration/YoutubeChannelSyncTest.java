package com.lyreo.lesson.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class YoutubeChannelSyncTest {
  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final BackgroundJobService jobs = mock(BackgroundJobService.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final JobExecutionContext context = mock(JobExecutionContext.class);
  private YoutubeChannelSync sync;
  private MockRestServiceServer server;
  private final String channel = "UCabcdefghijklmnopqrstuv";
  private final UUID jobId = UUID.randomUUID();
  private final BackgroundJob job =
      new BackgroundJob(
          jobId,
          "YOUTUBE_CHANNEL_SYNC",
          "lesson",
          UUID.randomUUID(),
          BackgroundJobStatus.RUNNING,
          25,
          null,
          0,
          1,
          3,
          null,
          "worker-a",
          null,
          null,
          null, null, java.time.Instant.EPOCH);

  @BeforeEach
  void setup() {
    when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    sync = new YoutubeChannelSync(jdbc, jobs, manager, new ObjectMapper(), "server-test-key");
    var builder = RestClient.builder().baseUrl("https://www.googleapis.com/youtube/v3");
    server = MockRestServiceServer.bindTo(builder).build();
    ReflectionTestUtils.setField(sync, "client", builder.build());
    when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
        .thenReturn(List.of(channel));
  }

  @Test
  void missingConfigurationAndMalformedIdsNeverEnqueue() {
    var disabled = new YoutubeChannelSync(jdbc, jobs, manager, new ObjectMapper(), "");
    assertThatThrownBy(() -> disabled.synchronize(List.of(channel)))
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> sync.synchronize(List.of("invalid")))
        .hasMessageContaining("YouTube channel IDs");
    verifyNoInteractions(jobs);
  }

  @Test
  void usesOneBatchedMetadataCallAndFencesEachCommit() throws Exception {
    server
        .expect(
            requestTo("https://www.googleapis.com/youtube/v3/channels?part=snippet&id=" + channel))
        .andExpect(header("X-Goog-Api-Key", "server-test-key"))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"id\":\""
                    + channel
                    + "\",\"snippet\":{\"title\":\"Updated"
                    + " channel\",\"thumbnails\":{\"high\":{\"url\":\"https://example.com/avatar.jpg\"}}}}]}",
                MediaType.APPLICATION_JSON));
    sync.execute(job, context);
    verify(jdbc)
        .update(
            contains("COALESCE(NULLIF(:avatar,''),avatar_url)"),
            argThat((Map<String, ?> values) -> "Updated channel".equals(values.get("title"))));
    verify(jobs, atLeastOnce()).fenceForCommit(jobId, "worker-a");
    server.verify();
  }

  @Test
  void missingChannelsRecordAnErrorWithoutReplacingGoodMetadata() throws Exception {
    server.expect(anything()).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
    sync.execute(job, context);
    verify(jdbc).update(contains("sync_error='Channel metadata was not returned'"), anyMap());
    verify(jdbc, never()).update(contains("avatar_url="), anyMap());
  }

  @Test
  void quotaWaitsDurablyAndDoesNotExposeProviderBodies() {
    server
        .expect(anything())
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private provider details"));
    assertThatThrownBy(() -> sync.execute(job, context))
        .isInstanceOf(DeferredJobException.class)
        .hasMessageNotContaining("private");
    verify(jdbc, never()).update(anyString(), anyMap());
  }

  @Test
  void nonQuotaPermissionFailureDoesNotRetryBlindly() {
    server
        .expect(anything())
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .body("{\"error\":{\"errors\":[{\"reason\":\"forbidden\"}]}}"));
    assertThatThrownBy(() -> sync.execute(job, context))
        .isInstanceOf(PermanentJobException.class)
        .hasMessageContaining("permissions");
  }

  @Test
  void cancellationDiscardsReturnedMetadata() {
    when(context.isCancellationRequested()).thenReturn(true);
    doCallRealMethod().when(context).throwIfCancellationRequested();
    assertThatThrownBy(() -> sync.execute(job, context)).isInstanceOf(JobCancelledException.class);
    verify(jdbc, never()).update(anyString(), anyMap());
  }
}
