package com.lyreo.platform.storage;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "lyreo.storage.mode", havingValue = "local", matchIfMissing = true)
public class LocalStorageConfiguration {
    @Bean
    ObjectStoragePort localObjectStoragePort(
        @Value("${lyreo.storage.local-root:../../.data/storage}") String root
    ) {
        return new LocalFileObjectStorageAdapter(Path.of(root));
    }
}
