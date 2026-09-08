package com.lyreo.platform.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConditionalOnProperty(name = "lyreo.storage.mode", havingValue = "r2")
public class S3StorageConfiguration {

    private StaticCredentialsProvider credentials(String id, String secret) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(id, secret));
    }

    @Bean
    S3Client lyreoS3Client(
        @Value("${lyreo.storage.endpoint}") URI endpoint,
        @Value("${lyreo.storage.access-key-id}") String accessKey,
        @Value("${lyreo.storage.secret-access-key}") String secret,
        @Value("${lyreo.storage.region:auto}") String region
    ) {
        return S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(credentials(accessKey, secret))
            .build();
    }

    @Bean
    S3Presigner lyreoS3Presigner(
        @Value("${lyreo.storage.endpoint}") URI endpoint,
        @Value("${lyreo.storage.access-key-id}") String accessKey,
        @Value("${lyreo.storage.secret-access-key}") String secret,
        @Value("${lyreo.storage.region:auto}") String region
    ) {
        return S3Presigner.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(credentials(accessKey, secret))
            .build();
    }

    @Bean
    ObjectStoragePort objectStoragePort(
        S3Client client,
        S3Presigner presigner,
        @Value("${lyreo.storage.bucket}") String bucket
    ) {
        return new S3ObjectStorageAdapter(client, presigner, bucket);
    }
}
