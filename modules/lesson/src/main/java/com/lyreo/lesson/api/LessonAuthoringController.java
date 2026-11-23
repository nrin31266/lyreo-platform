package com.lyreo.lesson.api;

import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.application.authoring.UploadedCaptions;
import com.lyreo.lesson.application.build.SourceMaterializationException;
import com.lyreo.contracts.errors.ServiceUnavailableException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lesson-authoring")
@PreAuthorize("hasRole('ADMIN')")
public class LessonAuthoringController {
    private final LessonAuthoringService service;
    private final com.lyreo.lesson.application.port.LessonCatalogPort catalog;
    public LessonAuthoringController(LessonAuthoringService service,com.lyreo.lesson.application.port.LessonCatalogPort catalog) {this.service=service;this.catalog=catalog;}
    @PostMapping("/inspect") public LessonAuthoringService.Inspect inspect(@Valid @RequestBody InspectRequest request) {
        return youtubeSource(() -> service.inspect(request.sourceReference()));
    }
    private static <T> T youtubeSource(Supplier<T> action) {
        try { return action.get(); }
        catch (SourceMaterializationException failure) {
            throw new ServiceUnavailableException(failure.errorCode(),
                "YouTube source is unavailable. Check the source and media tools, then retry.", failure);
        }
    }
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/drafts") public ResponseEntity<LessonAuthoringService.Accepted> create(@Valid @RequestBody CreateRequest request) {
        var result=youtubeSource(() -> service.create(request.title(),request.sourceReference(),request.startMs(),request.endMs(),
            request.options(),Boolean.TRUE.equals(request.preferEnglishCaptions()),request.captionFile()));
        catalog.updateMetadata(result.lessonId(),null,request.description()==null ? "" : request.description(),request.imageUrl(),request.categoryIds()==null ? List.of() : request.categoryIds());
        return accepted(result);
    }
    @GetMapping("/{id}") public LessonAuthoringResponse get(@PathVariable UUID id) {return LessonAuthoringResponse.from(service.get(id));}
    @PutMapping("/{id}/transcript") public LessonAuthoringResponse transcript(@PathVariable UUID id,@Valid @RequestBody TranscriptRequest request) {
        return LessonAuthoringResponse.from(service.transcript(id,request.revision(),request.sentences()));
    }
    @PostMapping("/{id}/approve") public LessonAuthoringResponse approve(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return LessonAuthoringResponse.from(service.approve(id,request.revision()));
    }
    @PostMapping("/{id}/align") public ResponseEntity<LessonAuthoringService.Accepted> align(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return accepted(service.align(id,request.revision()));
    }
    @PostMapping("/{id}/prepare") public ResponseEntity<LessonAuthoringService.Accepted> prepare(@PathVariable UUID id,@RequestBody PrepareRequest request) {
        return accepted(service.prepare(id,request.revision(),Boolean.TRUE.equals(request.useWhisperX())));
    }
    @PostMapping("/{id}/karaoke-review") public LessonAuthoringResponse karaoke(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return LessonAuthoringResponse.from(service.karaoke(id,request.revision()));
    }
    @PutMapping("/{id}/sentences/{sentenceId}/timing") public LessonAuthoringResponse timing(@PathVariable UUID id,@PathVariable UUID sentenceId,@RequestBody TimingRequest request) {
        return LessonAuthoringResponse.from(service.timing(id,request.revision(),sentenceId,request.words()));
    }
    @PutMapping("/{id}/content-options") public LessonAuthoringResponse contentOptions(@PathVariable UUID id,@RequestBody ContentOptionsRequest request) {
        return LessonAuthoringResponse.from(service.contentOptions(id,request.revision(),request.options()));
    }
    @PostMapping("/{id}/content") public ResponseEntity<LessonAuthoringService.Accepted> content(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return accepted(service.generateContent(id,request.revision()));
    }
    @PutMapping("/{id}/sentences/{sentenceId}/content") public LessonAuthoringResponse content(@PathVariable UUID id,@PathVariable UUID sentenceId,@RequestBody ContentRequest request) {
        return LessonAuthoringResponse.from(service.saveContent(id,request.revision(),sentenceId,request.content()));
    }
    @PostMapping("/{id}/content-review") public LessonAuthoringResponse contentReview(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return LessonAuthoringResponse.from(service.reviewContent(id,request.revision()));
    }
    public record ContentOptionsRequest(long revision,com.lyreo.lesson.domain.authoring.AuthoringOptions options) {}
    public record ContentRequest(long revision,LessonAuthoringService.ContentInput content) {}
    @PutMapping("/{id}/content-profile") public LessonAuthoringResponse profile(@PathVariable UUID id,@RequestBody ProfileRequest request) {
        return LessonAuthoringResponse.from(service.saveProfile(id,request.revision(),request.cefr(),request.contentType()));
    }
    public record ProfileRequest(long revision,com.lyreo.lesson.domain.authoring.AuthoringProfile.Cefr cefr,
        com.lyreo.lesson.domain.authoring.AuthoringProfile.ContentType contentType) {}
    @PostMapping("/{id}/cancel") public LessonAuthoringResponse cancel(@PathVariable UUID id,@RequestBody RevisionRequest request) {return LessonAuthoringResponse.from(service.cancelChain(id,request.revision()));}
    @PostMapping("/{id}/run") public ResponseEntity<LessonAuthoringService.Accepted> run(@PathVariable UUID id,@RequestBody RevisionRequest request) {return accepted(service.run(id,request.revision()));}
    @GetMapping("/{id}/readiness") public java.util.Map<String,Object> readiness(@PathVariable UUID id) {var errors=service.readiness(service.get(id));return java.util.Map.of("ready",errors.isEmpty(),"blockers",errors);}
    @PostMapping("/{id}/publish") public LessonAuthoringResponse publish(@PathVariable UUID id,@RequestBody RevisionRequest request,org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken authentication) {return LessonAuthoringResponse.from(service.publish(id,request.revision(),UUID.fromString(authentication.getToken().getSubject())));}
    @PostMapping("/{id}/duplicate") public LessonAuthoringResponse duplicate(@PathVariable UUID id,@RequestBody RevisionRequest request) {return LessonAuthoringResponse.from(service.duplicate(id,request.revision()));}
    @GetMapping("/{id}/jobs/{job}/diagnostics") public java.util.List<java.util.Map<String,Object>> diagnostics(@PathVariable UUID id,@PathVariable UUID job) {return service.diagnostics(id,job);}
    private static ResponseEntity<LessonAuthoringService.Accepted> accepted(LessonAuthoringService.Accepted result) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+result.jobId())).body(result);
    }
    public record TimingRequest(long revision,List<com.lyreo.lesson.domain.authoring.AuthoringSentence.Word> words) {}
    public record InspectRequest(@NotBlank String sourceReference) {}
    public record CreateRequest(@jakarta.validation.constraints.Size(max=500) String title,@NotBlank String sourceReference,long startMs,long endMs,String description,String imageUrl,List<UUID> categoryIds,com.lyreo.lesson.domain.authoring.AuthoringOptions options,Boolean preferEnglishCaptions,UploadedCaptions.File captionFile) {}
    public record PrepareRequest(long revision,Boolean useWhisperX) {}
    public record RevisionRequest(long revision) {}
    public record TranscriptRequest(long revision,@NotNull List<LessonAuthoringService.SentenceInput> sentences) {}
}
