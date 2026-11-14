package com.lyreo.lesson.api;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.application.port.AuthoringMediaPort;
import com.lyreo.lesson.application.port.VoiceSamplesPort;
import com.lyreo.lesson.application.port.LessonCatalogPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/lesson-authoring")
@PreAuthorize("hasRole('ADMIN')")
public class LessonMediaController {
    private final AuthoringMediaPort media;
    private final LessonAuthoringService service;
    private final LessonCatalogPort catalog;
    private final VoiceSamplesPort samples;
    public LessonMediaController(AuthoringMediaPort media,LessonAuthoringService service,LessonCatalogPort catalog,VoiceSamplesPort samples) {
        this.media=media;this.service=service;this.catalog=catalog;this.samples=samples;
    }
    @GetMapping("/voices") public VoiceSamplesPort.Catalog voices() {return samples.catalog();}
    @PostMapping("/voices/samples") public ResponseEntity<java.util.Map<String,UUID>> prepareSamples() {
        UUID id=samples.prepare();return ResponseEntity.accepted().body(java.util.Map.of("jobId",id));
    }
    @GetMapping("/voices/{voice}/sample") public ResponseEntity<ByteArrayResource> sample(@PathVariable String voice) {
        return audio(samples.audio(voice),"audio/wav");
    }
    private void requireVoice(String voice) {
        if(media.voices().voices().stream().noneMatch(item->item.id().equals(voice)))
            throw new RequestValidationException("Choose a supported Kokoro US/UK voice");
    }
    @PostMapping("/text-drafts") @Transactional
    public ResponseEntity<LessonAuthoringResponse> text(@Valid @RequestBody TextRequest request) {
        requireVoice(request.voice());
        var draft=service.createText(request.title(),request.text(),request.voice());
        catalog.updateMetadata(draft.id,null,request.description()==null ? "" : request.description(),request.imageUrl(),request.categoryIds()==null ? List.of() : request.categoryIds());
        return ResponseEntity.created(URI.create("/api/v1/admin/lesson-authoring/"+draft.id)).body(LessonAuthoringResponse.from(draft));
    }
    @PutMapping("/{id}/text") public LessonAuthoringResponse textSource(@PathVariable UUID id,@Valid @RequestBody TextSourceRequest request) {
        return LessonAuthoringResponse.from(service.text(id,request.revision(),request.text()));
    }
    public record TextSourceRequest(long revision,@NotBlank @jakarta.validation.constraints.Size(max=10000) String text) {}
    @PutMapping("/{id}/voice") public LessonAuthoringResponse voice(@PathVariable UUID id,@RequestBody VoiceRequest request) {
        requireVoice(request.voice());return LessonAuthoringResponse.from(service.voice(id,request.revision(),request.voice()));
    }
    @PostMapping("/{id}/synthesize") public ResponseEntity<LessonAuthoringService.Accepted> synthesize(@PathVariable UUID id,@RequestBody Revision request) {
        requireVoice(service.get(id).ttsVoice);
        var accepted=service.synthesize(id,request.revision());
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+accepted.jobId())).body(accepted);
    }
    @PostMapping(value="/audio-sources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AuthoringMediaPort.Source> upload(@RequestPart("file") MultipartFile file) throws IOException {
        if(file.isEmpty() || file.getSize()>30L*1024*1024) throw new RequestValidationException("Audio upload must contain 1–30 MiB");
        var source=media.upload(file.getOriginalFilename(),file.getBytes());
        return ResponseEntity.created(URI.create("/api/v1/admin/lesson-authoring/audio-sources/"+source.id())).body(source);
    }
    @GetMapping("/audio-sources/{id}") public AuthoringMediaPort.Source source(@PathVariable UUID id) {return media.source(id);}
    @GetMapping("/audio-sources/{id}/audio") public ResponseEntity<ByteArrayResource> sourceAudio(@PathVariable UUID id) {
        return audio(media.readSource(id),media.source(id).contentType());
    }
    @PostMapping("/audio-drafts") @Transactional
    public ResponseEntity<LessonAuthoringService.Accepted> audioDraft(@Valid @RequestBody AudioRequest request) {
        var source=media.source(request.sourceId());
        var accepted=service.createAudio(request.title(),source.id(),source.durationMs(),request.startMs(),request.endMs(),source.originalFilename());
        catalog.updateMetadata(accepted.lessonId(),null,request.description()==null ? "" : request.description(),request.imageUrl(),request.categoryIds()==null ? List.of() : request.categoryIds());
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+accepted.jobId())).body(accepted);
    }
    @GetMapping("/{id}/audio") public ResponseEntity<ByteArrayResource> lessonAudio(@PathVariable UUID id) {
        var lesson=service.get(id);
        if(lesson.audioObjectKey==null) throw new ResourceNotFoundException("Prepared audio not found");
        return audio(media.readAudio(lesson.audioObjectKey),"audio/wav");
    }
    private static ResponseEntity<ByteArrayResource> audio(byte[] bytes,String type) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(type))
            .contentLength(bytes.length).body(new ByteArrayResource(bytes));
    }
    public record TextRequest(@jakarta.validation.constraints.Size(max=500) String title,@NotBlank String text,@NotBlank String voice,List<UUID> categoryIds,String description,String imageUrl) {}
    public record AudioRequest(@jakarta.validation.constraints.Size(max=500) String title,@NotNull UUID sourceId,long startMs,long endMs,List<UUID> categoryIds,String description,String imageUrl) {}
    public record VoiceRequest(long revision,String voice) {}
    public record Revision(long revision) {}
}
