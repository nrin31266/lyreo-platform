package com.lyreo.lesson.infrastructure;

import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.infrastructure.integration.AuthoringYoutubeSource;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class YoutubeAuthoringConfiguration {
    @Bean
    AuthoringSourcePort authoringSourcePort(NamedParameterJdbcTemplate jdbc, ObjectStoragePort storage,
        ObjectMapper mapper, @Value("${lyreo.lesson.youtube.enabled:false}") boolean enabled,
        @Value("${lyreo.lesson.youtube.command:yt-dlp}") String command,
        @Value("${lyreo.lesson.youtube.timeout-seconds:300}") long timeout) {
        return new AuthoringYoutubeSource(jdbc, storage, mapper, enabled, command,
            Duration.ofSeconds(Math.max(30, timeout)));
    }
}
