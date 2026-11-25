package com.lyreo.chat.api;

import com.lyreo.chat.application.EnglishTutorService;
import com.lyreo.chat.domain.ChatConversation;
import com.lyreo.chat.domain.ChatConversationDetail;
import com.lyreo.identity.application.AppUserProvisioningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Learner-owned English tutor conversation API. */
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
    public List<ChatConversation> listConversations(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return tutor.listConversations(learnerId(jwt), page, size);
    }

    @GetMapping("/conversations/{id}")
    public ChatConversationDetail getConversation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return tutor.getConversation(learnerId(jwt), id);
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record AskRequest(
        UUID conversationId,
        String learnerLevel,
        @NotBlank(message = "question is required")
        String question,
        String title
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
