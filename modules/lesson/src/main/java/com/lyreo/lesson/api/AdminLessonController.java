package com.lyreo.lesson.api;

import com.lyreo.lesson.application.CreateLessonBuildService;
import com.lyreo.lesson.application.LessonPreviewQuery;
import com.lyreo.lesson.domain.LessonActivityType;
import com.lyreo.lesson.domain.LessonAnnotationType;
import com.lyreo.lesson.domain.LessonBuildOptions;
import com.lyreo.lesson.domain.LessonSourceType;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/lessons")
public class AdminLessonController {
    private final CreateLessonBuildService service;
    private final LessonPreviewQuery preview;

    public AdminLessonController(CreateLessonBuildService service, LessonPreviewQuery preview) {
        this.service = service;
        this.preview = preview;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable UUID id) {
        return preview.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/build")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateLessonBuildService.BuildAccepted> build(@RequestBody BuildLessonRequest request) {
        var options = new LessonBuildOptions(
            request.sourceType(),
            request.activities(),
            request.annotations(),
            request.accent(),
            request.pronunciationStrategy()
        );
        var accepted = service.create(
            request.title(),
            request.sourceText(),
            request.sourceReference(),
            options
        );
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/jobs/" + accepted.jobId()))
            .body(accepted);
    }

    public record BuildLessonRequest(
        String title,
        LessonSourceType sourceType,
        String sourceText,
        String sourceReference,
        Set<LessonActivityType> activities,
        Set<LessonAnnotationType> annotations,
        String accent,
        String pronunciationStrategy
    ) {}
}
