package com.lyreo.grammar.api;

import com.lyreo.grammar.application.GrammarPracticeFilter;
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
    public List<GrammarQuestionResponse> questions(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) UUID topicCatalogId,
        @RequestParam(required = false) UUID subtopicCatalogId,
        @RequestParam(required = false) UUID bankCatalogId,
        @RequestParam(required = false) Integer difficultyLevel,
        @RequestParam(required = false) Integer limit
    ) {
        UUID learnerId = users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
        return practice.practice(
            new GrammarPracticeFilter(topicCatalogId, subtopicCatalogId, bankCatalogId, difficultyLevel),
            limit,
            learnerId
        ).stream().map(GrammarQuestionResponse::from).toList();
    }

    @PostMapping("/questions/{itemId}/attempts")
    public GrammarSubmitResponse submit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID itemId,
        @Valid @RequestBody SubmitRequest request
    ) {
        UUID learnerId = users.provision(
            jwt.getSubject(),
            jwt.getClaimAsString("email")
        ).id();
        return GrammarSubmitResponse.from(practice.submit(learnerId, itemId, request.answer()));
    }

    public record SubmitRequest(
        @NotBlank(message = "answer is required")
        String answer
    ) {}

    public record QuestionOptionResponse(
        String key,
        String text
    ) {}

    public record GrammarQuestionResponse(
        UUID itemId,
        String questionText,
        List<QuestionOptionResponse> options,
        int difficultyLevel,
        UUID topicCatalogId,
        UUID subtopicCatalogId
    ) {
        public static GrammarQuestionResponse from(GrammarPracticeService.QuestionView view) {
            return new GrammarQuestionResponse(
                view.itemId(),
                view.questionText(),
                view.options().stream()
                    .map(o -> new QuestionOptionResponse(o.key(), o.text()))
                    .toList(),
                view.difficultyLevel(),
                view.topicCatalogId(),
                view.subtopicCatalogId()
            );
        }
    }

    public record GrammarSubmitResponse(
        UUID attemptId,
        UUID itemId,
        boolean correct,
        String correctAnswer,
        String explanationVi,
        String translationVi,
        String answerTranslationVi,
        String vocabularyNote,
        String explanationPolicy
    ) {
        public static GrammarSubmitResponse from(GrammarPracticeService.SubmitResult result) {
            return new GrammarSubmitResponse(
                result.attemptId(),
                result.itemId(),
                result.correct(),
                result.correctAnswer(),
                result.explanationVi(),
                result.translationVi(),
                result.answerTranslationVi(),
                result.vocabularyNote(),
                result.explanationPolicy() != null ? result.explanationPolicy().name() : null
            );
        }
    }
}
