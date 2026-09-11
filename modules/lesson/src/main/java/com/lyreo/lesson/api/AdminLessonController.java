package com.lyreo.lesson.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lesson.application.CreateLessonBuildService;
import com.lyreo.lesson.application.LessonPreviewService;
import com.lyreo.lesson.application.LessonPreviewView;
import com.lyreo.lesson.domain.LessonActivityType;
import com.lyreo.lesson.domain.LessonAnnotationType;
import com.lyreo.lesson.domain.LessonBuildOptions;
import com.lyreo.lesson.domain.LessonSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/lessons")
public class AdminLessonController {
    private final CreateLessonBuildService service;
    private final LessonPreviewService preview;

    public AdminLessonController(CreateLessonBuildService service, LessonPreviewService preview) {
        this.service = service;
        this.preview = preview;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public LessonPreviewResponse preview(@PathVariable UUID id) {
        return preview.find(id)
            .map(LessonPreviewResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("Lesson preview not found: " + id));
    }

    @PostMapping("/build")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BuildAcceptedResponse> build(@Valid @RequestBody BuildLessonRequest request) {
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
            .body(new BuildAcceptedResponse(accepted.lessonId(), accepted.jobId()));
    }

    public record LessonPreviewResponse(
        UUID id,
        String title,
        String source_type,
        String source_text,
        String source_reference,
        String canonical_audio_object_key,
        String status,
        Instant created_at,
        Instant updated_at,
        List<SentenceResponse> sentences,
        List<ActivityResponse> activities,
        List<BuildJobResponse> buildJobs
    ) {
        public static LessonPreviewResponse from(LessonPreviewView view) {
            return new LessonPreviewResponse(
                view.id(),
                view.title(),
                view.sourceType(),
                view.sourceText(),
                view.sourceReference(),
                view.canonicalAudioObjectKey(),
                view.status(),
                view.createdAt(),
                view.updatedAt(),
                view.sentences() == null ? List.of() : view.sentences().stream().map(SentenceResponse::from).toList(),
                view.activities() == null ? List.of() : view.activities().stream().map(ActivityResponse::from).toList(),
                view.buildJobs() == null ? List.of() : view.buildJobs().stream().map(BuildJobResponse::from).toList()
            );
        }

        public record SentenceResponse(
            UUID id,
            int position,
            String text,
            Integer audio_start_ms,
            Integer audio_end_ms,
            String audio_clip_object_key,
            List<WordResponse> words,
            List<AnnotationResponse> annotations
        ) {
            public static SentenceResponse from(LessonPreviewView.SentenceView s) {
                return new SentenceResponse(
                    s.id(),
                    s.position(),
                    s.text(),
                    s.audioStartMs(),
                    s.audioEndMs(),
                    s.audioClipObjectKey(),
                    s.words() == null ? List.of() : s.words().stream().map(WordResponse::from).toList(),
                    s.annotations() == null ? List.of() : s.annotations().stream().map(AnnotationResponse::from).toList()
                );
            }
        }

        public record WordResponse(
            int position,
            String surface_text,
            Integer start_ms,
            Integer end_ms
        ) {
            public static WordResponse from(LessonPreviewView.WordView w) {
                return new WordResponse(w.position(), w.surfaceText(), w.startMs(), w.endMs());
            }
        }

        public record AnnotationResponse(
            String annotation_type,
            Object payload,
            String generated_by,
            String provider,
            String model,
            String status,
            Instant created_at
        ) {
            public static AnnotationResponse from(LessonPreviewView.AnnotationView a) {
                return new AnnotationResponse(
                    a.annotationType(),
                    a.payload(),
                    a.generatedBy(),
                    a.provider(),
                    a.model(),
                    a.status(),
                    a.createdAt()
                );
            }
        }

        public record ActivityResponse(
            UUID id,
            String activity_type,
            int position,
            boolean enabled,
            String config_json
        ) {
            public static ActivityResponse from(LessonPreviewView.ActivityView a) {
                return new ActivityResponse(a.id(), a.activityType(), a.position(), a.enabled(), a.configJson());
            }
        }

        public record BuildJobResponse(
            UUID job_id,
            Instant created_at,
            String status,
            String current_step,
            Integer progress_percent,
            Integer attempt_count,
            String error_message
        ) {
            public static BuildJobResponse from(LessonPreviewView.BuildJobView b) {
                return new BuildJobResponse(
                    b.jobId(),
                    b.createdAt(),
                    b.status(),
                    b.currentStep(),
                    b.progressPercent(),
                    b.attemptCount(),
                    b.errorMessage()
                );
            }
        }
    }

    public record BuildAcceptedResponse(
        UUID lessonId,
        UUID jobId
    ) {}

    public record BuildLessonRequest(
        @NotBlank(message = "title is required")
        String title,
        @NotNull(message = "sourceType is required")
        LessonSourceType sourceType,
        String sourceText,
        String sourceReference,
        Set<LessonActivityType> activities,
        Set<LessonAnnotationType> annotations,
        String accent,
        String pronunciationStrategy
    ) {}
}
