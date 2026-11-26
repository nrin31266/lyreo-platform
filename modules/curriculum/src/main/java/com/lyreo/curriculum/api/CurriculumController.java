package com.lyreo.curriculum.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.curriculum.application.CurriculumService;
import com.lyreo.curriculum.domain.ContinueLearningView;
import com.lyreo.curriculum.domain.CurriculumPathDetail;
import com.lyreo.curriculum.domain.CurriculumPathSummary;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.AssetStore;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/curriculum")
public class CurriculumController {
    private final CurriculumService curriculum;
    private final AppUserProvisioningService users;
    private final AssetStore assets;

    public CurriculumController(
        CurriculumService curriculum,
        AppUserProvisioningService users
    ) {
        this(curriculum, users, (AssetStore) null);
    }

    @Autowired
    public CurriculumController(
        CurriculumService curriculum,
        AppUserProvisioningService users,
        ObjectProvider<AssetStore> assetsProvider
    ) {
        this(curriculum, users, assetsProvider.getIfAvailable());
    }

    public CurriculumController(
        CurriculumService curriculum,
        AppUserProvisioningService users,
        AssetStore assets
    ) {
        this.curriculum = curriculum;
        this.users = users;
        this.assets = assets;
    }

    @GetMapping("/paths")
    public List<CurriculumPathSummary> listPaths(@AuthenticationPrincipal Jwt jwt) {
        return curriculum.listPublishedPaths(learnerId(jwt));
    }

    @GetMapping("/paths/{id}")
    public CurriculumPathDetail getPath(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return curriculum.getPublishedPathDetail(learnerId(jwt), id);
    }

    @GetMapping("/paths/{id}/cover")
    public ResponseEntity<byte[]> cover(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        CurriculumPathDetail detail = curriculum.getPublishedPathDetail(learnerId(jwt), id);
        if (detail.coverAssetId() == null || assets == null) {
            throw new ResourceNotFoundException("Curriculum cover image not found");
        }
        AssetStore.Asset meta = assets.get(detail.coverAssetId());
        byte[] bytes = assets.bytes(detail.coverAssetId());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(meta.mimeType()))
            .cacheControl(CacheControl.noCache().mustRevalidate())
            .eTag("\"" + detail.coverAssetId() + "-" + meta.sizeBytes() + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes);
    }

    @PostMapping("/paths/{id}/enroll")
    public CurriculumPathDetail enroll(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return curriculum.enroll(learnerId(jwt), id);
    }

    @GetMapping("/continue")
    public ContinueLearningView continueLearning(@AuthenticationPrincipal Jwt jwt) {
        return curriculum.continueLearning(learnerId(jwt));
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }
}
