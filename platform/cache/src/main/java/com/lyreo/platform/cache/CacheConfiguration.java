package com.lyreo.platform.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfiguration {

    @Bean
    CacheManager lyreoCacheManager() {
        var manager = new CaffeineCacheManager(
            "lexiconHotEntries",
            "grammarTaxonomy",
            "curriculumMetadata",
            "aiRouting",
            "runtimeConfig"
        );
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterAccess(Duration.ofMinutes(30)));
        return manager;
    }
}
