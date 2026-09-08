package com.lyreo.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HexFormat;
import java.security.MessageDigest;

/**
 * Zero-dependency development adapter. It is deliberately not a production CDN.
 * Local Core + local FastAPI can share the absolute file URI on the same machine.
 */
public final class LocalFileObjectStorageAdapter implements ObjectStoragePort {
    private final Path root;

    public LocalFileObjectStorageAdapter(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create local storage root " + this.root, failure);
        }
    }

    @Override
    public StoredObject put(String objectKey, String contentType, byte[] content) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new StoredObject(objectKey, content.length, sha256(content));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to write local object " + objectKey, failure);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read local object " + objectKey, failure);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to delete local object " + objectKey, failure);
        }
    }

    @Override
    public URI createDownloadUrl(String objectKey, Duration ttl) {
        return resolve(objectKey).toUri();
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey is required");
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Object key escapes configured storage root");
        }
        return resolved;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
