package com.lyreo.grammar.api;

import com.lyreo.grammar.application.GrammarPracticeRepository.PracticeFilter;
import com.lyreo.grammar.application.GrammarPracticeService;
import com.lyreo.identity.application.AppUserProvisioningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/grammar")
public class GrammarPracticeController {
    private final GrammarPracticeService practice;
    private final AppUserProvisioningService users;

    public GrammarPracticeController(
        GrammarPracticeService practice,
        AppUserProvisioningService users
    ) {
        this.practice = practice;
        this.users = users;
    }

    @GetMapping("/questions")
    public List<GrammarPracticeService.QuestionView> questions(
        @RequestParam(required = false) UUID topicId,
        @RequestParam(required = false) UUID subtopicId,
        @RequestParam(required = false) UUID bankSetId,
        @RequestParam(required = false) Integer difficulty,
        @RequestParam(required = false) Integer limit
    ) {
        return practice.practice(
            new PracticeFilter(topicId, subtopicId, bankSetId, difficulty),
            limit
        );
    }

    @PostMapping("/questions/{questionId}/attempts")
    public GrammarPracticeService.SubmitResult submit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID questionId,
        @Valid @RequestBody SubmitRequest request
    ) {
        UUID learnerId = users.provision(
            jwt.getSubject(),
            jwt.getClaimAsString("email")
        ).id();
        return practice.submit(learnerId, questionId, request.answer());
    }

    public record SubmitRequest(
        @NotBlank(message = "answer is required")
        String answer
    ) {}
}
