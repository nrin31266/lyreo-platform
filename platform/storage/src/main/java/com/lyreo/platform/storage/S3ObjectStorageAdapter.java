package com.lyreo.platform.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public final class S3ObjectStorageAdapter implements ObjectStoragePort {
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorageAdapter(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public StoredObject put(String objectKey, String contentType, byte[] content) {
        var request = PutObjectRequest.builder()
            .bucket(bucket).key(objectKey).contentType(contentType).build();
        var response = client.putObject(request, RequestBody.fromBytes(content));
        return new StoredObject(objectKey, content.length, response.eTag());
    }

    @Override
    public InputStream get(String objectKey) {
        var bytes = client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        return new ByteArrayInputStream(bytes.asByteArray());
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    @Override
    public URI createDownloadUrl(String objectKey, Duration ttl) {
        var get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        var request = GetObjectPresignRequest.builder()
            .signatureDuration(ttl).getObjectRequest(get).build();
        return URI.create(presigner.presignGetObject(request).url().toString());
    }
}
