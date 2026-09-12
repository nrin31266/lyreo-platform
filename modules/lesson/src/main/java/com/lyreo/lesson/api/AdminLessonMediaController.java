package com.lyreo.lesson.api;

import com.lyreo.lesson.application.LessonMediaUploadService;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin upload boundary for Lesson-owned authoring media (AUDIO/IMAGE).
 * The returned download URL is temporary convenience for immediate AI/preview use
 * and is never persisted as canonical state.
 */
@RestController
@RequestMapping("/api/v1/admin/lessons")
public class AdminLessonMediaController {
    private final LessonMediaUploadService media;

    public AdminLessonMediaController(LessonMediaUploadService media) {
        this.media = media;
    }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public MediaUploadResponse upload(
        @RequestParam("kind") String kind,
        @RequestPart("file") MultipartFile file
    ) {
        try {
            var uploaded = media.upload(kind, file.getBytes());
            return new MediaUploadResponse(
                uploaded.objectKey(),
                uploaded.contentType(),
                uploaded.size(),
                uploaded.sha256(),
                uploaded.downloadUrl()
            );
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read uploaded media", failure);
        }
    }

    public record MediaUploadResponse(
        String objectKey,
        String contentType,
        long size,
        String sha256,
        String downloadUrl
    ) {}
}
