package com.lyreo.lesson.api;

import com.lyreo.lesson.application.port.SourcePreviewPort;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lesson-source-previews")
@PreAuthorize("hasRole('ADMIN')")
public class SourcePreviewController {
    private final SourcePreviewPort previews;
    public SourcePreviewController(SourcePreviewPort previews) {this.previews=previews;}
    @PostMapping public ResponseEntity<SourcePreviewPort.Preview> create(@jakarta.validation.Valid @RequestBody Request input) {
        SourcePreviewPort.Preview preview;
        try {preview=previews.create(input.sourceReference(),input.startMs(),input.endMs());}
        catch(com.lyreo.lesson.application.build.SourceMaterializationException failure) {
            throw new com.lyreo.contracts.errors.ServiceUnavailableException(failure.errorCode(),"YouTube source is unavailable. Check the source and media tools, then retry.",failure);
        }
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+preview.jobId())).body(preview);
    }
    @GetMapping("/{id}") public SourcePreviewPort.Preview get(@PathVariable UUID id) {return previews.get(id);}
    @GetMapping("/{id}/audio") public ResponseEntity<Resource> audio(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("audio/wav"))
            .body(new ByteArrayResource(previews.audio(id)));
    }
    public record Request(@jakarta.validation.constraints.NotBlank String sourceReference,long startMs,long endMs) {}
}
