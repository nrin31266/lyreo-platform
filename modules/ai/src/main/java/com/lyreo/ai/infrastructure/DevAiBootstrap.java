package com.lyreo.ai.infrastructure;

import com.lyreo.ai.application.AiAdminService;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;

/**
 * Deterministic local route bootstrap so a fresh developer database can build lessons without
 * downloading Qwen or configuring paid/free-tier credentials first.
 */
@Profile("dev")
public final class DevAiBootstrap implements ApplicationRunner {
    private static final List<String> CAPABILITIES = List.of(
        "STT",
        "ALIGNMENT",
        "TTS",
        "NLP",
        "GENERAL_LLM",
        "REASONING_LLM",
        "PRONUNCIATION_JUDGE"
    );

    private final AiAdminService admin;

    public DevAiBootstrap(AiAdminService admin) {
        this.admin = admin;
    }

    @Override
    public void run(ApplicationArguments args) {
        admin.saveProvider(
            "DEV_MOCK",
            "Lyreo development mock",
            null,
            null,
            true
        );
        for (String capability : CAPABILITIES) {
            admin.saveRoute(capability, "DEV_MOCK", "mock", 10, false, true);
        }
    }
}
