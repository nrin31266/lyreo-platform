package com.lyreo.chat.api;

import com.lyreo.chat.application.EnglishTutorService;
import com.lyreo.chat.domain.ChatConversation;
import com.lyreo.chat.domain.ChatConversationDetail;
import com.lyreo.identity.application.AppUserProvisioningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Learner-owned English tutor conversation API. */
@Validated
@RestController
@RequestMapping("/api/v1/chat")
public class EnglishTutorController {
    private final EnglishTutorService tutor;
    private final AppUserProvisioningService users;

    public EnglishTutorController(
        EnglishTutorService tutor,
        AppUserProvisioningService users
    ) {
        this.tutor = tutor;
        this.users = users;
    }

    @PostMapping("/ask")
    public AskResponse ask(
        @AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AskRequest request
    ) {
        EnglishTutorService.AskResult result = tutor.ask(
            learnerId(jwt),
            request.conversationId(),
            request.learnerLevel(),
            request.question(),
            request.title(),
            idempotencyKey
        );
        return new AskResponse(
            result.conversationId(),
            result.messageId(),
            result.answer(),
            result.aiInvocationId(),
            result.remainingQuota(),
            result.quotaResetAt()
        );
    }

    @GetMapping("/conversations")
    public ConversationPageResponse listConversations(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        EnglishTutorService.ConversationPage result = tutor.listConversationsPage(learnerId(jwt), page, size);
        return new ConversationPageResponse(
            result.items(),
            result.total(),
            result.page(),
            result.size(),
            result.hasMore()
        );
    }

    @GetMapping("/conversations/{id}")
    public ChatConversationDetail getConversation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return tutor.getConversation(learnerId(jwt), id);
    }

    @PatchMapping("/conversations/{id}")
    public ChatConversation renameConversation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody RenameConversationRequest request
    ) {
        return tutor.renameConversation(learnerId(jwt), id, request.title());
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        tutor.deleteConversation(learnerId(jwt), id);
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record AskRequest(
        UUID conversationId,
        @Size(max = 20)
        String learnerLevel,
        @NotBlank(message = "question is required")
        @Size(max = 2000)
        String question,
        @Size(max = 120)
        String title
    ) {}

    public record RenameConversationRequest(
        @NotBlank(message = "title is required")
        @Size(max = 120)
        String title
    ) {}

    public record ConversationPageResponse(
        List<ChatConversation> items,
        long total,
        int page,
        int size,
        boolean hasMore
    ) {}

    public record AskResponse(
        UUID conversationId,
        UUID messageId,
        String answer,
        UUID aiInvocationId,
        int remainingQuota,
        Instant quotaResetAt
    ) {}
}
