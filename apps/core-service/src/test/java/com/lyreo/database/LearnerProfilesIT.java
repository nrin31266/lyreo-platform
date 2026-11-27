package com.lyreo.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.contracts.errors.ServiceUnavailableException;
import com.lyreo.identity.api.AdminLearnerAccountController;
import com.lyreo.identity.application.LearnerAccountService;
import com.lyreo.identity.application.port.LearnerAccountProvider;
import com.lyreo.identity.infrastructure.IdentityConfiguration;
import com.lyreo.identity.infrastructure.persistence.JdbcLearnerAccountStore;
import com.lyreo.identity.infrastructure.persistence.JpaAppUserEntity;
import com.lyreo.identity.infrastructure.persistence.SpringDataAppUserJpaRepository;
import com.lyreo.learner.api.AdminLearnerController;
import com.lyreo.learner.api.LearnerController;
import com.lyreo.learner.infrastructure.LearnerConfiguration;
import com.lyreo.platform.bootstrap.ApiRateLimitConfiguration;
import com.lyreo.platform.bootstrap.OpenApiConfiguration;
import com.lyreo.platform.bootstrap.SecurityConfiguration;
import com.lyreo.platform.web.ApiExceptionHandler;
import com.lyreo.platform.web.ApiProblemWriter;
import com.lyreo.platform.web.ProblemAccessDeniedHandler;
import com.lyreo.platform.web.ProblemAuthenticationEntryPoint;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LearnerProfilesIT.Config.class, properties = "springdoc.api-docs.enabled=true")
class LearnerProfilesIT {
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");
    static { postgres.start(); }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = SpringDataAppUserJpaRepository.class)
    @EntityScan(basePackageClasses = JpaAppUserEntity.class)
    @Import({
        IdentityConfiguration.class, LearnerConfiguration.class, LearnerController.class, AdminLearnerController.class,
        SecurityConfiguration.class, ApiRateLimitConfiguration.class, OpenApiConfiguration.class,
        ApiExceptionHandler.class, ApiProblemWriter.class, ProblemAccessDeniedHandler.class,
        ProblemAuthenticationEntryPoint.class, AdminLearnerAccountController.class,
        LearnerAccountService.class, JdbcLearnerAccountStore.class
    })
    static class Config {
        @Bean FakeAccountProvider testAccountProvider() { return new FakeAccountProvider(); }
    }

    static class FakeAccountProvider implements LearnerAccountProvider, LearnerAccountProvider.Connection {
        UUID subject;
        boolean enabled = true;
        boolean failConnect;
        boolean failLogout;
        boolean temporary;
        @Override public boolean configured() { return true; }
        @Override public Optional<Connection> connect(UUID id) {
            if (failConnect) throw new ServiceUnavailableException("KEYCLOAK_ACCOUNT_UNAVAILABLE", "Unavailable", null);
            return id.equals(subject) ? Optional.of(this) : Optional.empty();
        }
        @Override public boolean enabled() { return enabled; }
        @Override public void enable(boolean next) { enabled = next; }
        @Override public void endSessions() {
            if (failLogout) throw new ServiceUnavailableException("KEYCLOAK_ACCOUNT_UNAVAILABLE", "Unavailable", null);
        }
        @Override public void resetPassword(String password, boolean changeOnNextLogin) {
            temporary = changeOnNextLogin;
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FakeAccountProvider provider;
    MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void resetsPasswordsWithEitherOptionAndKeepsSessionsBlockedAcrossLockAndUnlock() throws Exception {
        UUID learner = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,keycloak_subject) VALUES (?,?)", learner, subject.toString());
        provider.subject = subject;
        var admin = auth("account-management-admin", "ADMIN");
        var issued = Instant.now().minusSeconds(30);
        var oldToken = jwt().jwt(token -> token.subject(subject.toString()).issuedAt(issued))
            .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
        String account = "/api/v1/admin/learners/" + learner + "/account";
        String reset = account + "/password";
        String lock = account + "/lock";
        for (boolean temporary : new boolean[] { true, false }) {
            String passwordPayload = json.writeValueAsString(java.util.Map.of(
                "password", "Fixture-Password!87", "changeOnNextLogin", temporary));
            mvc.perform(put(reset).with(oldToken).contentType(MediaType.APPLICATION_JSON).content(passwordPayload))
                .andExpect(status().isForbidden());
            mvc.perform(put(reset).with(admin).contentType(MediaType.APPLICATION_JSON).content(passwordPayload))
                .andExpect(status().isNoContent());
            assertThat(provider.temporary).isEqualTo(temporary);
        }

        String lockRequest = """
            {"locked":true}
            """;
        String unlockRequest = """
            {"locked":false,"reason":"  Test account recovery  "}
            """;
        provider.failConnect = true;
        try {
            mvc.perform(put(lock).with(admin).contentType(MediaType.APPLICATION_JSON).content(lockRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.syncPending").value(true));
            mvc.perform(get("/api/v1/learner/profile").with(oldToken))
                .andExpect(status().isUnauthorized());
        } finally {
            provider.failConnect = false;
        }
        provider.failLogout = true;
        try {
            mvc.perform(put(lock).with(admin).contentType(MediaType.APPLICATION_JSON).content(lockRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.syncPending").value(true));
            assertThat(jdbc.queryForList("SELECT action FROM admin_activity_directory WHERE learner_id = ?", String.class, learner))
                .contains("LOCK_REQUESTED");
            mvc.perform(get("/api/v1/learner/profile").with(oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
            mvc.perform(put(lock).with(admin).contentType(MediaType.APPLICATION_JSON).content(unlockRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syncPending").value(true));
            assertThat(jdbc.queryForList("SELECT action FROM admin_activity_directory WHERE learner_id = ?", String.class, learner))
                .contains("UNLOCK_REQUESTED").doesNotContain("LOCK_REQUESTED");
            assertThat(provider.enabled).isFalse();
        } finally {
            provider.failLogout = false;
        }

        mvc.perform(put(lock).with(admin).contentType(MediaType.APPLICATION_JSON).content(lockRequest))
            .andExpect(status().isOk()).andExpect(jsonPath("$.syncPending").value(false));
        mvc.perform(put(lock).with(admin).contentType(MediaType.APPLICATION_JSON).content(unlockRequest))
            .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(false));
        assertThat(provider.enabled).isTrue();
        assertThat(jdbc.queryForList("SELECT action FROM admin_activity_directory WHERE learner_id = ?", String.class, learner))
            .contains("LOCKED", "UNLOCKED").doesNotContain("LOCK_REQUESTED", "UNLOCK_REQUESTED");
        mvc.perform(get("/api/v1/learner/profile").with(oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/learner/profile").with(jwt().jwt(token -> token.subject(subject.toString())
                .issuedAt(Instant.now().plusSeconds(2))).authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action FROM learner_account_audit WHERE learner_id = ? ORDER BY id", String.class, learner))
            .contains("TEMPORARY_PASSWORD_SET", "PASSWORD_SET", "LOCKED", "UNLOCKED");
        assertThat(jdbc.queryForObject("SELECT reason FROM learner_account_audit WHERE learner_id = ? AND action = 'LOCKED'",
            String.class, learner)).isNull();
        assertThat(jdbc.queryForObject("SELECT reason FROM learner_account_audit WHERE learner_id = ? AND action = 'UNLOCKED'",
            String.class, learner)).isEqualTo("Test account recovery");
    }

    @Test
    void doesNotFallbackLockUnverifiedOrDemotedAccountsWhenKeycloakIsUnavailable() throws Exception {
        UUID unverified = UUID.randomUUID();
        UUID unverifiedSubject = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,keycloak_subject) VALUES (?,?)", unverified, unverifiedSubject.toString());
        var admin = auth("account-guard-admin", "ADMIN");
        var unverifiedToken = jwt().jwt(token -> token.subject(unverifiedSubject.toString())
                .issuedAt(Instant.now().minusSeconds(10)))
            .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
        String lockUrl = "/api/v1/admin/learners/" + unverified + "/account/lock";

        provider.failConnect = true;
        try {
            mvc.perform(put(lockUrl).with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
                .andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT account_locked FROM app_user WHERE id = ?", Boolean.class, unverified))
                .isFalse();
            assertThat(jdbc.queryForObject("SELECT account_sync_pending FROM app_user WHERE id = ?", Boolean.class, unverified))
                .isFalse();
            mvc.perform(get("/api/v1/learner/profile").with(unverifiedToken))
                .andExpect(status().isOk());
        } finally {
            provider.failConnect = false;
        }

        // Verify once via status(), then revoke manageability (simulating admin promotion) and ensure fallback fails closed again
        provider.subject = unverifiedSubject;
        mvc.perform(get("/api/v1/admin/learners/" + unverified + "/account").with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.management").value("AVAILABLE"));

        provider.subject = UUID.randomUUID();
        mvc.perform(get("/api/v1/admin/learners/" + unverified + "/account").with(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.management").value("UNMANAGED"));

        provider.failConnect = true;
        try {
            mvc.perform(put(lockUrl).with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
                .andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT account_locked FROM app_user WHERE id = ?", Boolean.class, unverified))
                .isFalse();
        } finally {
            provider.failConnect = false;
        }
    }

    @Test
    void adminCanReadButCannotChangeAToeicTarget() throws Exception {
        var learner = auth("toeic-target-learner", "LEARNER");
        var admin = auth("toeic-target-admin", "ADMIN");
        var profile = mvc.perform(get("/api/v1/learner/profile").with(learner))
            .andExpect(status().isOk()).andReturn();
        String id = json.readTree(profile.getResponse().getContentAsString()).get("userId").asString();
        jdbc.update("UPDATE learner_profile SET toeic_target_score = 750 WHERE learner_id = ?", UUID.fromString(id));
        String directoryUrl = "/api/v1/admin/learners/" + id;
        mvc.perform(put(directoryUrl + "/toeic-target").with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"score\":800}"))
            .andExpect(status().isNotFound());
        mvc.perform(get(directoryUrl).with(admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.toeicTargetScore").value(750));
    }

    @Test
    void tracksLearnersBeforeOnboardingAndPreservesTheirEditsAndPreferencesOnRetry() throws Exception {
        var learner = auth("profiles-ada", "LEARNER");
        var other = auth("profiles-other", "LEARNER");
        var admin = auth("profiles-admin", "ADMIN");
        var initial = mvc.perform(get("/api/v1/learner/profile").with(learner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(false))
            .andExpect(jsonPath("$.profile").value(nullValue())).andReturn();
        String id = json.readTree(initial.getResponse().getContentAsString()).get("userId").asString();

        mvc.perform(get("/api/v1/admin/learners").with(admin).param("query", "PROFILES-ADA").param("status", "PENDING"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].learnerId").value(id));
        mvc.perform(patch("/api/v1/learner/profile").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Ada"}
                    """))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/learner/preferences").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "preferredAccent":"UK","translation":"AFTER_ATTEMPT","sentenceIpa":"TAP_TO_SHOW",
                      "vocabularyNotes":"AFTER_ATTEMPT","grammarNotes":"AFTER_ATTEMPT",
                      "thoughtGroups":false,"karaokeHighlighting":true,"properNounHints":true,"defaultPlaybackSpeed":1.25
                    }
                    """))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/learner/profile").with(learner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(false));

        String onboarding = """
            {"goal":"WORK","currentLevel":"B2","dailyMinutes":15}
            """;
        mvc.perform(put("/api/v1/learner/onboarding").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content(onboarding))
            .andExpect(status().isOk()).andExpect(jsonPath("$.starterSuggestion.topic").value("WORK"))
            .andExpect(jsonPath("$.starterSuggestion.difficulty").value("INTERMEDIATE"))
            .andExpect(jsonPath("$.preferences.defaultPlaybackSpeed").value(1.25));
        var completedAt = jdbc.queryForObject("SELECT onboarding_completed_at FROM learner_profile WHERE learner_id = ?",
            java.sql.Timestamp.class, UUID.fromString(id));
        mvc.perform(patch("/api/v1/learner/profile").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":" Ada ","goal":"UNDECIDED","currentLevel":"UNKNOWN","dailyMinutes":20,"focusArea":"LISTENING"}
                    """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Ada"))
            .andExpect(jsonPath("$.starterSuggestion.topic").value("CONVERSATION"))
            .andExpect(jsonPath("$.starterSuggestion.difficulty").value("BEGINNER"));
        mvc.perform(put("/api/v1/learner/onboarding").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content(onboarding))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dailyMinutes").value(20))
            .andExpect(jsonPath("$.displayName").value("Ada")).andExpect(jsonPath("$.focusArea").value("LISTENING"))
            .andExpect(jsonPath("$.preferences.defaultPlaybackSpeed").value(1.25));
        mvc.perform(patch("/api/v1/learner/profile").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"focusArea":null}
                    """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.focusArea").value(nullValue()))
            .andExpect(jsonPath("$.displayName").value("Ada"));
        mvc.perform(patch("/api/v1/learner/profile").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentLevel":null}
                    """))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT onboarding_completed_at FROM learner_profile WHERE learner_id = ?",
            java.sql.Timestamp.class, UUID.fromString(id))).isEqualTo(completedAt);

        mvc.perform(get("/api/v1/learner/profile").with(other).param("learnerId", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(false))
            .andExpect(jsonPath("$.profile").value(nullValue()));
        mvc.perform(get("/api/v1/admin/learners").with(admin).param("query", "Ada").param("status", "COMPLETED")
                .param("size", "1").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/admin/learners/{id}", id).with(admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("profiles-ada@example.test"))
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETED")).andExpect(jsonPath("$.dailyMinutes").value(20));
        mvc.perform(get("/api/v1/admin/learners/{id}", UUID.randomUUID()).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/learners/{id}", id).with(learner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/learner/profile").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/learners")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/learners").with(admin).param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    void legacyProfileAppearsAfterVerifiedLearnerAccessWithoutLosingCompletion() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, keycloak_subject, email_snapshot) VALUES (?, ?, ?)",
            id, "profiles-legacy", "original@example.test");
        jdbc.update("""
            INSERT INTO learner_profile (learner_id, display_name, current_level, goal, daily_minutes, onboarding_completed_at)
            VALUES (?, 'Learner', 'C1', 'Career', 30, now())
            """, id);

        var admin = auth("profiles-admin", "ADMIN");
        mvc.perform(get("/api/v1/admin/learners/{id}", id).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/learner/profile").with(auth("profiles-legacy", "LEARNER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(true))
            .andExpect(jsonPath("$.profile.goal").value("Career"))
            .andExpect(jsonPath("$.profile.starterSuggestion.difficulty").value("ADVANCED"));
        mvc.perform(get("/api/v1/admin/learners/{id}", id).with(admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("original@example.test"))
            .andExpect(jsonPath("$.onboardingStatus").value("COMPLETED"));
    }

    @Test
    void letsOwnersAndAdminsEditAppearanceWhileKeepingUploadsPrivate() throws Exception {
        var learner = auth("profiles-avatar", "LEARNER");
        var other = auth("profiles-avatar-other", "LEARNER");
        var admin = auth("profiles-admin", "ADMIN");
        var onboarded = mvc.perform(put("/api/v1/learner/onboarding").with(learner)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"goal":"WORK","currentLevel":"B2","dailyMinutes":15}
                    """))
            .andExpect(status().isOk()).andReturn();
        String id = json.readTree(onboarded.getResponse().getContentAsString()).get("learnerId").asString();
        String detail = "/api/v1/admin/learners/" + id;
        mvc.perform(patch(detail).with(other).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"avatarPreset":"CAT"}
                    """))
            .andExpect(status().isForbidden());
        mvc.perform(patch(detail).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Demo Avatar","avatarPreset":"FOX","goal":"TOEIC"}
                    """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.avatarPreset").value("FOX"))
            .andExpect(jsonPath("$.displayName").value("Demo Avatar"))
            .andExpect(jsonPath("$.goal").value("WORK"));
        mvc.perform(patch("/api/v1/learner/profile").with(learner).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"avatarPreset":"FLOWER_IRISES"}
                    """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.avatarPreset").value("FLOWER_IRISES"));
        mvc.perform(patch(detail).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"avatarPreset":"FLOWER_UNKNOWN"}
                    """))
            .andExpect(status().isBadRequest());
        String invalidName = """
            {"displayName":"Demo\\u0000Avatar"}
            """;
        mvc.perform(patch(detail).with(admin).contentType(MediaType.APPLICATION_JSON).content(invalidName))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/learner/profile").with(learner)
                .contentType(MediaType.APPLICATION_JSON).content(invalidName))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/learners").with(admin).param("query", "Demo\0Avatar"))
            .andExpect(status().isBadRequest());

        ByteArrayOutputStream original = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", original);
        var png = new MockMultipartFile("file", "avatar.png", "image/png", original.toByteArray());
        mvc.perform(multipart(HttpMethod.PUT, detail + "/avatar").file(png).with(admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.avatarUploadId").isString())
            .andExpect(jsonPath("$.avatarPreset").value(nullValue()));

        var image = mvc.perform(get("/api/v1/learner/profile/avatar").with(learner))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG)).andReturn();
        var decoded = ImageIO.read(new ByteArrayInputStream(image.getResponse().getContentAsByteArray()));
        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(decoded.getHeight()).isEqualTo(256);
        mvc.perform(get("/api/v1/learner/profile/avatar").with(other)).andExpect(status().isNotFound());
        mvc.perform(get(detail + "/avatar").with(other)).andExpect(status().isForbidden());
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/learner/profile/avatar")
                .file(new MockMultipartFile("file", "invalid.png", "image/png", new byte[]{1, 2})).with(learner))
            .andExpect(status().isBadRequest());
        mvc.perform(patch(detail).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"avatarPreset":null}
                    """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.avatarUploadId").value(nullValue()));
        mvc.perform(get(detail + "/avatar").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void exposesLearnerOperationsInOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs").param("fresh", "learner-contract"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/learner/profile'].patch.responses['409']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/learner/profile/avatar'].put.responses['409']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/admin/learners/{learnerId}/avatar'].get.responses['404']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/admin/learners'].get.tags[0]").value("Learner Administration"))
            .andExpect(jsonPath("$.components.schemas.LearnerProfileResponse.required").value(hasItem("profile")))
            .andExpect(jsonPath("$.components.schemas.LearnerProfileResponse.properties.profile.anyOf[0]['$ref']")
                .value("#/components/schemas/LearnerProfileDto"))
            .andExpect(jsonPath("$.components.schemas.LearnerProfileResponse.properties.profile.anyOf[1].type").value("null"))
            .andExpect(jsonPath("$.components.schemas.LearnerProfilePatchRequest.properties.focusArea.type").value(hasItem("null")))
            .andExpect(jsonPath("$.components.schemas.LearnerAppearancePatchRequest.properties.avatarPreset.type").value(hasItem("null")))
            .andExpect(jsonPath("$.components.schemas.Entry.properties.email.type").value(hasItem("null")));
    }

    private static RequestPostProcessor auth(String subject, String role) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
