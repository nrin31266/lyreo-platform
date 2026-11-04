package com.lyreo.lesson.api;

import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.application.build.SourceMaterializationException;
import com.lyreo.contracts.errors.ServiceUnavailableException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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
        try { return service.inspect(request.sourceReference()); }
        catch (SourceMaterializationException failure) {
            throw new ServiceUnavailableException(failure.errorCode(),
                "YouTube source is unavailable. Check the source and media tools, then retry.", failure);
        }
    }
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/drafts") public ResponseEntity<LessonAuthoringService.Accepted> create(@Valid @RequestBody CreateRequest request) {
        var result=service.create(request.title(),request.sourceReference(),request.startMs(),request.endMs());
        catalog.updateMetadata(result.lessonId(),request.title(),request.description()==null ? "" : request.description(),request.imageUrl(),request.categoryIds()==null ? List.of() : request.categoryIds());
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
    @PostMapping("/{id}/prepare") public ResponseEntity<LessonAuthoringService.Accepted> prepare(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return accepted(service.prepare(id,request.revision()));
    }
    @PostMapping("/{id}/karaoke-review") public LessonAuthoringResponse karaoke(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return LessonAuthoringResponse.from(service.karaoke(id,request.revision()));
    }
    @PostMapping("/{id}/generate") public ResponseEntity<LessonAuthoringService.Accepted> generate(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return accepted(service.generate(id,request.revision()));
    }
    @PutMapping("/{id}/candidates") public LessonAuthoringResponse candidates(@PathVariable UUID id,@RequestBody CandidatesRequest request) {
        return LessonAuthoringResponse.from(service.candidates(id,request.revision(),request.dictationSentenceIds(),request.shadowingSentenceIds()));
    }
    @PutMapping("/{id}/sentences/{sentenceId}/timing") public LessonAuthoringResponse timing(@PathVariable UUID id,@PathVariable UUID sentenceId,@RequestBody TimingRequest request) {
        return LessonAuthoringResponse.from(service.timing(id,request.revision(),sentenceId,request.words()));
    }
    @PostMapping("/{id}/publish") public LessonAuthoringResponse publish(@PathVariable UUID id,@RequestBody RevisionRequest request) {
        return LessonAuthoringResponse.from(service.publish(id,request.revision()));
    }
    private static ResponseEntity<LessonAuthoringService.Accepted> accepted(LessonAuthoringService.Accepted result) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+result.jobId())).body(result);
    }
    public record TimingRequest(long revision,List<com.lyreo.lesson.domain.authoring.AuthoringSentence.Word> words) {}
    public record InspectRequest(@NotBlank String sourceReference) {}
    public record CreateRequest(@NotBlank String title,@NotBlank String sourceReference,long startMs,long endMs,String description,String imageUrl,List<UUID> categoryIds) {}
    public record RevisionRequest(long revision) {}
    public record TranscriptRequest(long revision,@NotNull List<LessonAuthoringService.SentenceInput> sentences) {}
    public record CandidatesRequest(long revision,List<UUID> dictationSentenceIds,List<UUID> shadowingSentenceIds) {}
}
