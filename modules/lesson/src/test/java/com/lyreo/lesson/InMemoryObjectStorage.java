package com.lyreo.lesson;

import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for ObjectStoragePort; also records put calls for assertions. */
public final class InMemoryObjectStorage implements ObjectStoragePort {
    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public StoredObject put(String objectKey, String contentType, byte[] content) {
        objects.put(objectKey, content);
        return new StoredObject(objectKey, content.length, null);
    }

    @Override
    public InputStream get(String objectKey) {
        byte[] bytes = objects.get(objectKey);
        if (bytes == null) throw new IllegalStateException("missing " + objectKey);
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
    }

    @Override
    public URI createDownloadUrl(String objectKey, Duration ttl) {
        return URI.create("https://storage.invalid/" + objectKey);
    }
}
