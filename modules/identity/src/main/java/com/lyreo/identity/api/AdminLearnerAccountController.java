package com.lyreo.identity.api;

import com.lyreo.identity.application.LearnerAccountService;
import com.lyreo.identity.application.LearnerAccountService.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/learners/{learnerId}/account")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLearnerAccountController {
    private final LearnerAccountService accounts;

    public AdminLearnerAccountController(LearnerAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public Status get(@PathVariable UUID learnerId, @AuthenticationPrincipal Jwt admin) {
        return accounts.status(learnerId, admin.getSubject());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable UUID learnerId, @AuthenticationPrincipal Jwt admin,
                              @Valid @RequestBody ResetPassword request) {
        accounts.resetPassword(learnerId, admin.getSubject(), request.password(), request.changeOnNextLogin());
    }

    @PutMapping("/lock")
    public Status setLocked(@PathVariable UUID learnerId, @AuthenticationPrincipal Jwt admin,
                            @Valid @RequestBody SetLock request) {
        String reason = request.reason();
        return accounts.setLocked(learnerId, admin.getSubject(), request.locked(),
            reason == null || reason.isBlank() ? null : reason.strip());
    }

    public record ResetPassword(@NotBlank @Size(max = 256) String password, @NotNull Boolean changeOnNextLogin) {
        @Override public String toString() { return "ResetPassword[redacted]"; }
    }

    public record SetLock(@NotNull Boolean locked, @Size(max = 500) String reason) {}
}
