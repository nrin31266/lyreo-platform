package com.lyreo.curriculum.api;

import com.lyreo.curriculum.application.CurriculumService;
import com.lyreo.curriculum.domain.ContinueLearningView;
import com.lyreo.curriculum.domain.CurriculumPathDetail;
import com.lyreo.curriculum.domain.CurriculumPathSummary;
import com.lyreo.identity.application.AppUserProvisioningService;
import java.util.List;
import java.util.UUID;
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

    public CurriculumController(
        CurriculumService curriculum,
        AppUserProvisioningService users
    ) {
        this.curriculum = curriculum;
        this.users = users;
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
