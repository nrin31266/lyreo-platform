package com.lyreo.chat.api;

import com.lyreo.chat.application.EnglishTutorService;
import com.lyreo.chat.domain.AdminChatSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Privacy-preserving operational telemetry for English tutor conversations. */
@RestController
@RequestMapping("/api/v1/admin/chat")
@PreAuthorize("hasRole('ADMIN')")
public class AdminChatController {
    private final EnglishTutorService tutor;

    public AdminChatController(EnglishTutorService tutor) {
        this.tutor = tutor;
    }

    @GetMapping("/summary")
    public AdminChatSummary summary() {
        return tutor.adminSummary();
    }
}
