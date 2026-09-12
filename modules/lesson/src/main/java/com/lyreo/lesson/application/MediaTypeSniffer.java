package com.lyreo.lesson.application;

import java.util.Optional;

/**
 * Content-type detection from magic bytes for lesson authoring media.
 *
 * <p>The client-provided filename/MIME are never trusted: a wrong label would poison
 * the canonical audio contract for STT/alignment. Only formats accepted by the
 * Lesson preparation workflow are detected; everything else is rejected.</p>
 */
public final class MediaTypeSniffer {

    private MediaTypeSniffer() {}

    public static Optional<String> sniff(byte[] bytes) {
        if (bytes == null) return Optional.empty();
        if (hasPrefix(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of("image/jpeg");
        }
        if (hasPrefix(bytes, new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Optional.of("image/png");
        }
        if (hasPrefix(bytes, new byte[]{0x52, 0x49, 0x46, 0x46}) && bytes.length >= 12) {
            if (hasPrefix(bytes, 8, new byte[]{0x57, 0x45, 0x42, 0x50})) {
                return Optional.of("image/webp");
            }
            if (hasPrefix(bytes, 8, new byte[]{0x57, 0x41, 0x56, 0x45})) {
                return Optional.of("audio/wav");
            }
        }
        if (hasPrefix(bytes, new byte[]{0x49, 0x44, 0x33})) {
            return Optional.of("audio/mpeg");
        }
        if (bytes.length >= 2
            && (bytes[0] & 0xFF) == 0xFF
            && ((bytes[1] & 0xE0) == 0xE0)) {
            return Optional.of("audio/mpeg");
        }
        if (bytes.length >= 12 && hasPrefix(bytes, 4, new byte[]{0x66, 0x74, 0x79, 0x70})) {
            return Optional.of("audio/mp4");
        }
        if (hasPrefix(bytes, new byte[]{0x4F, 0x67, 0x67, 0x53})) {
            return Optional.of("audio/ogg");
        }
        if (hasPrefix(bytes, new byte[]{0x66, 0x4C, 0x61, 0x43})) {
            return Optional.of("audio/flac");
        }
        if (hasPrefix(bytes, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})) {
            return Optional.of("audio/webm");
        }
        return Optional.empty();
    }

    private static boolean hasPrefix(byte[] bytes, byte[] prefix) {
        return hasPrefix(bytes, 0, prefix);
    }

    private static boolean hasPrefix(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length < offset + prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[offset + index] != prefix[index]) return false;
        }
        return true;
    }
}
