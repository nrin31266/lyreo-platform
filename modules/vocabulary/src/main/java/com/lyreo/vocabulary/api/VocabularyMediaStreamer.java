package com.lyreo.vocabulary.api;

import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.ByteRangeResponses;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

/** Shared Byte-Range and security-header response builder for vocabulary media assets. */
final class VocabularyMediaStreamer {

    private VocabularyMediaStreamer() {}

    static ResponseEntity<Resource> stream(AssetStore.Asset asset, byte[] bytes, String rangeHeader) {
        return ByteRangeResponses.stream(asset, bytes, rangeHeader);
    }
}
