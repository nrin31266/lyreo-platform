package com.lyreo.speechassessment.api;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.speechassessment.application.AdminPronunciationAttempts;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPronunciationController {
    private static final String SENTENCE = "/lesson-authoring/{lessonId}/sentences/{sentenceId}/pronunciation-attempts";
    private static final String ATTEMPT = "/pronunciation-attempts/{id}";
    private final AdminPronunciationAttempts attempts;

    public AdminPronunciationController(AdminPronunciationAttempts attempts) {
        this.attempts = attempts;
    }

    @GetMapping(SENTENCE)
    public AdminPronunciationAttempts.Constraints constraints(@PathVariable UUID lessonId, @PathVariable UUID sentenceId,
        @RequestParam long revision) {
        return attempts.constraints(lessonId, sentenceId, revision);
    }

    @PostMapping(value = SENTENCE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AdminPronunciationAttempts.Accepted> submit(@PathVariable UUID lessonId, @PathVariable UUID sentenceId,
        @RequestHeader("Idempotency-Key") UUID key, @RequestParam long revision, @RequestPart("audio") MultipartFile audio,
        JwtAuthenticationToken authentication) throws IOException {
        if (audio.isEmpty() || audio.getSize() > attempts.maxUploadBytes()) {
            throw new RequestValidationException("Recording size exceeds the allowed upload limits");
        }
        var accepted = attempts.submit(authentication.getToken().getSubject(), key, lessonId, sentenceId, revision, audio.getBytes());
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/admin/pronunciation-attempts/" + accepted.attemptId()))
            .cacheControl(CacheControl.noStore()).body(accepted);
    }

    @GetMapping(ATTEMPT)
    public ResponseEntity<AdminPronunciationAttempts.View> view(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(attempts.view(authentication.getToken().getSubject(), id));
    }

    @GetMapping(ATTEMPT + "/audio")
    public ResponseEntity<byte[]> audio(@PathVariable UUID id, JwtAuthenticationToken authentication) throws IOException {
        byte[] data = attempts.recording(authentication.getToken().getSubject(), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType("audio/wav")).contentLength(data.length).body(data);
    }

    @PostMapping(ATTEMPT + "/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        attempts.cancel(authentication.getToken().getSubject(), id);
        return ResponseEntity.accepted().build();
    }
}
