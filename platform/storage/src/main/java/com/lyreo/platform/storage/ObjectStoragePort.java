package com.lyreo.platform.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

public interface ObjectStoragePort {
    StoredObject put(String objectKey, String contentType, byte[] content);
    InputStream get(String objectKey);
    void delete(String objectKey);
    URI createDownloadUrl(String objectKey, Duration ttl);

    record StoredObject(String objectKey, long size, String eTag) {}
}
