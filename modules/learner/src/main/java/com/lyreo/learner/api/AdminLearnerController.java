package com.lyreo.learner.api;

import com.lyreo.learner.application.LearnerProfileService;
import com.lyreo.learner.domain.LearnerDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/learners")
public class AdminLearnerController {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminLearnerController.class);
    private final LearnerProfileService profiles;

    public AdminLearnerController(LearnerProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public LearnerDirectory list(
        @RequestParam(defaultValue = "") @Size(max = 120) String query,
        @RequestParam(required = false) LearnerDirectory.Status status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return profiles.directory(query, status, page, size);
    }

    @GetMapping("/{learnerId}")
    public LearnerDirectory.Entry detail(@PathVariable UUID learnerId) {
        return profiles.directoryEntry(learnerId);
    }

    @PatchMapping("/{learnerId}")
    public LearnerDirectory.Entry edit(
        @PathVariable UUID learnerId, @AuthenticationPrincipal Jwt admin,
        @Valid @RequestBody LearnerAppearancePatchRequest request
    ) {
        var appearance = request.appearance();
        var saved = profiles.patchAppearance(learnerId, appearance);
        LOG.info("Admin edited learner appearance actor={} learnerId={} displayName={} avatar={}",
            admin.getSubject(), learnerId, appearance.changeName(), appearance.changeAvatar());
        return saved;
    }

    @PutMapping(value = "/{learnerId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LearnerDirectory.Entry uploadAvatar(
        @PathVariable UUID learnerId, @AuthenticationPrincipal Jwt admin, @RequestPart("file") MultipartFile file
    ) {
        var saved = profiles.uploadDirectoryAvatar(learnerId, LearnerAvatarImage.normalize(file));
        LOG.info("Admin uploaded learner avatar actor={} learnerId={}", admin.getSubject(), learnerId);
        return saved;
    }

    @GetMapping(value = "/{learnerId}/avatar", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> avatar(@PathVariable UUID learnerId) {
        return LearnerAvatarImage.response(profiles.avatar(learnerId));
    }
}
