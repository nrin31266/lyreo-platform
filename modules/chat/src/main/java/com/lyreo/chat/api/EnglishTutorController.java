package com.lyreo.chat.api;

import com.lyreo.chat.application.EnglishTutorService;
import java.util.Map;
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
    public Map<String, String> ask(@RequestBody AskRequest request) {
        return Map.of("answer", tutor.ask(request.learnerLevel(), request.question()));
    }

    public record AskRequest(String learnerLevel, String question) {}
}
