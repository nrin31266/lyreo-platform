package com.lyreo.vocabulary.api;

import com.lyreo.platform.content.AssetStore;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Shared Byte-Range and security-header response builder for vocabulary media assets. */
final class VocabularyMediaStreamer {

    private VocabularyMediaStreamer() {}

    static ResponseEntity<Resource> stream(AssetStore.Asset asset, byte[] bytes, String rangeHeader) {
        MediaType mediaType = MediaType.parseMediaType(asset.mimeType());
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
        }

        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException ex) {
            return unsatisfiable(bytes.length);
        }

        if (ranges.size() != 1 || bytes.length == 0) {
            return unsatisfiable(bytes.length);
        }

        HttpRange range = ranges.getFirst();
        long start = range.getRangeStart(bytes.length);
        long end = range.getRangeEnd(bytes.length);
        if (start < 0 || start >= bytes.length || start > end) {
            return unsatisfiable(bytes.length);
        }

        int inclusiveEnd = (int) Math.min(end, bytes.length - 1L);
        byte[] slice = Arrays.copyOfRange(bytes, (int) start, inclusiveEnd + 1);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
            .contentType(mediaType)
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + inclusiveEnd + "/" + bytes.length)
            .contentLength(slice.length)
            .body(new ByteArrayResource(slice));
    }

    private static ResponseEntity<Resource> unsatisfiable(long totalLength) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalLength)
            .build();
    }
}
