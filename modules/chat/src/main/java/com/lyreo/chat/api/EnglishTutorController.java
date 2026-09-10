package com.lyreo.chat.api;

import com.lyreo.chat.application.EnglishTutorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Low-priority English-only tutor endpoint. */
@RestController
@RequestMapping("/api/v1/chat")
public class EnglishTutorController {
    private final EnglishTutorService tutor;

    public EnglishTutorController(EnglishTutorService tutor) {
        this.tutor = tutor;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return new AskResponse(tutor.ask(request.learnerLevel(), request.question()));
    }

    public record AskRequest(
        String learnerLevel,
        @NotBlank(message = "question is required")
        String question
    ) {}

    public record AskResponse(
        String answer
    ) {}
}
