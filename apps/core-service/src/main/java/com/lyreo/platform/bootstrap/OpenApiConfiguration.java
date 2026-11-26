package com.lyreo.platform.bootstrap;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI configuration for Springdoc.
 *
 * <p>The base {@link #lyreoOpenAPI()} bean declares API info, security scheme, and tags.
 * The {@link #lyreoOpenApiCustomizer()} registers Lyreo's programmatic component schemas
 * (LyreoProblemDetail, ApiFieldViolation) <em>at customizer time</em> — the same lifecycle
 * phase that adds {@code $ref} pointers — so springdoc cannot prune them between phases.
 *
 * <p>The custom schema is named {@code LyreoProblemDetail} (not generic {@code ProblemDetail})
 * to avoid collision with {@code org.springframework.http.ProblemDetail} which springdoc may
 * also generate from return-type scanning.
 */
@Configuration
public class OpenApiConfiguration {

    public static final String BEARER_AUTH = "BearerAuth";

    /** Component schema name for Lyreo's RFC 9457 extension payload. */
    public static final String PROBLEM_SCHEMA_NAME = "LyreoProblemDetail";

    /** JSON Pointer ref to the problem schema in components. */
    public static final String PROBLEM_SCHEMA_REF = "#/components/schemas/" + PROBLEM_SCHEMA_NAME;

    /** Component schema name for field-level validation violations. */
    public static final String VIOLATION_SCHEMA_NAME = "ApiFieldViolation";

    /** JSON Pointer ref to the field violation schema in components. */
    public static final String VIOLATION_SCHEMA_REF = "#/components/schemas/" + VIOLATION_SCHEMA_NAME;

    @Bean
    public OpenAPI lyreoOpenAPI() {
        SecurityScheme securityScheme = new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description("Keycloak / OIDC Bearer JWT token");

        return new OpenAPI()
            .info(new Info()
                .title("Lyreo Core API")
                .version("1.0.0")
                .description("Lyreo English-learning platform Core HTTP API services."))
            .components(new Components()
                .addSecuritySchemes(BEARER_AUTH, securityScheme))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
            .tags(List.of(
                new Tag().name("Identity").description("User identity and session endpoints"),
                new Tag().name("Learner").description("Learner profile, onboarding and preferences"),
                new Tag().name("Learner Administration").description("Learner directory and profile appearance"),
                new Tag().name("Lesson").description("Admin lesson authoring and build triggers"),
                new Tag().name("Lesson Practice").description("Learner lesson dictation and practice"),
                new Tag().name("Jobs").description("Background job tracking and cancellation"),
                new Tag().name("AI Administration").description("AI provider and route routing management"),
                new Tag().name("Lexicon").description("Global learner dictionary lookup"),
                new Tag().name("Vocabulary").description("Learner vocabulary SRS and review"),
                new Tag().name("Grammar").description("Grammar bank questions and practice"),
                new Tag().name("TOEIC").description("TOEIC test attempts and submission"),
                new Tag().name("Entitlement").description("Learner feature entitlements, quota snapshots and manual admin grants"),
                new Tag().name("Curriculum").description("Learning paths, section progression and admin authoring"),
                new Tag().name("Gamification").description("Missions, achievements, streaks, XP and Diamond ledger"),
                new Tag().name("Analytics").description("Learner study overview, metrics and weakness projections"),
                new Tag().name("Chat").description("English tutor conversation and AI voice roleplay scenarios"),
                new Tag().name("Realtime").description("Admin realtime SSE feeds and learner notifications")
            ));
    }

    @Bean
    public OpenApiCustomizer lyreoOpenApiCustomizer() {
        return openApi -> {
            // Register component schemas at customizer time, before adding any $ref.
            // This prevents springdoc from pruning schemas that have no references yet.
            ensureProblemSchemas(openApi);
            allowLexiconExampleProperties(openApi);
            describeLexiconSourcePos(openApi);

            if (openApi.getPaths() == null) {
                return;
            }

            var problemRef = new Schema<>().$ref(PROBLEM_SCHEMA_REF);
            var problemContent = new Content().addMediaType(
                "application/problem+json",
                new MediaType().schema(problemRef)
            );
            describeLearnerSchemas(openApi);
            describeLearnerOperations(openApi, problemContent);
            describePlatformOperations(openApi, problemContent);

            // Operation-level contract for POST /api/v1/admin/lessons/build
            var lessonBuildPath = openApi.getPaths().get("/api/v1/admin/lessons/build");
            if (lessonBuildPath != null && lessonBuildPath.getPost() != null) {
                var post = lessonBuildPath.getPost();
                ApiResponses responses = post.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    post.setResponses(responses);
                }

                ApiResponse response202 = responses.get("200");
                if (response202 != null) {
                    responses.remove("200");
                } else {
                    response202 = new ApiResponse();
                }
                response202.setDescription("Lesson build job accepted for asynchronous processing");
                response202.addHeaderObject(
                    "Location",
                    new Header()
                        .description("URI of the created background job to monitor status (e.g., /api/v1/jobs/{id})")
                        .schema(new StringSchema().example("/api/v1/jobs/123e4567-e89b-12d3-a456-426614174000"))
                );
                responses.addApiResponse("202", response202);

                responses.addApiResponse("400", new ApiResponse()
                    .description("Request validation failed")
                    .content(problemContent));
                responses.addApiResponse("401", new ApiResponse()
                    .description("Authentication required")
                    .content(problemContent));
                responses.addApiResponse("403", new ApiResponse()
                    .description("Access denied (ADMIN role required)")
                    .content(problemContent));
            }

            // Operation-level contract for POST /api/v1/jobs/{id}/cancel
            var jobCancelPath = openApi.getPaths().get("/api/v1/jobs/{id}/cancel");
            if (jobCancelPath != null && jobCancelPath.getPost() != null) {
                var post = jobCancelPath.getPost();
                ApiResponses responses = post.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    post.setResponses(responses);
                }
                responses.remove("200");
                ApiResponse response202 = new ApiResponse()
                    .description("Job cancellation requested successfully");
                responses.addApiResponse("202", response202);
                responses.addApiResponse("401", new ApiResponse()
                    .description("Authentication required")
                    .content(problemContent));
                responses.addApiResponse("403", new ApiResponse()
                    .description("Access denied (ADMIN role required)")
                    .content(problemContent));
                responses.addApiResponse("404", new ApiResponse()
                    .description("Background job not found")
                    .content(problemContent));
                responses.addApiResponse("409", new ApiResponse()
                    .description("Background job is not cancellable in its current state")
                    .content(problemContent));
            }

            // Operation security and rate limit documentation scoped to API paths
            openApi.getPaths().forEach((pathPattern, pathItem) -> {
                boolean isPublic = !pathPattern.startsWith("/api/");
                pathItem.readOperations().forEach(operation -> {
                    if (isPublic) {
                        operation.setSecurity(Collections.emptyList());
                    } else {
                        if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
                            operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
                        }
                        if (operation.getResponses() != null) {
                            operation.getResponses().addApiResponse("429", new ApiResponse()
                                .description("Rate limit quota exceeded")
                                .addHeaderObject("Retry-After", new Header()
                                    .description("Seconds to wait before retrying")
                                    .schema(new IntegerSchema().example(60)))
                                .content(problemContent));
                        }
                    }
                });
            });
        };
    }

    private static void describeLearnerSchemas(OpenAPI openApi) {
        var schemas = openApi.getComponents().getSchemas();
        if (schemas == null) return;

        var wrapper = schemas.get("LearnerProfileResponse");
        if (wrapper != null) {
            var profile = (Schema<?>) wrapper.getProperties().get("profile");
            wrapper.addProperty("profile", new Schema<>().anyOf(List.of(profile, new Schema<>().typesItem("null"))));
            wrapper.setRequired(List.of("userId", "onboarded", "profile"));
        }
        markNullable(schemas.get("LearnerProfilePatchRequest"), "string", "displayName", "focusArea", "avatarPreset");
        markNullable(schemas.get("LearnerAppearancePatchRequest"), "string", "displayName", "avatarPreset");
        markNullable(schemas.get("LearnerProfileDto"), "string",
            "displayName", "goal", "currentLevel", "focusArea", "avatarPreset", "avatarUploadId");
        markNullable(schemas.get("LearnerProfileDto"), "integer", "dailyMinutes");
        markNullable(schemas.get("StarterSuggestion"), "integer", "dailyMinutes");
        markNullable(schemas.get("Entry"), "string",
            "displayName", "email", "onboardingCompletedAt", "goal", "currentLevel", "focusArea", "avatarPreset", "avatarUploadId");
        markNullable(schemas.get("Entry"), "integer", "dailyMinutes");
    }

    private static void markNullable(Schema<?> schema, String type, String... fields) {
        if (schema == null) return;
        for (String field : fields) {
            schema.getProperties().get(field).setTypes(Set.of(type, "null"));
        }
    }

    private static void describeLearnerOperations(OpenAPI openApi, Content problems) {
        openApi.getPaths().forEach((path, item) -> {
            boolean admin = path.equals("/api/v1/admin/learners") || path.startsWith("/api/v1/admin/learners/");
            if (!admin && !path.startsWith("/api/v1/learner/")) return;
            item.readOperations().forEach(operation -> {
                operation.setTags(List.of(admin ? "Learner Administration" : "Learner"));
                operation.getResponses().addApiResponse("403", new ApiResponse()
                    .description(admin ? "ADMIN role required" : "LEARNER role required").content(problems));
            });
            if (path.equals("/api/v1/learner/profile")) {
                item.getGet().setDescription("Profile is null until the learner completes onboarding.");
                item.getPatch().setDescription("Omitted fields retain their values. Explicit null clears displayName, focusArea or avatarPreset.");
                item.getPatch().getResponses().addApiResponse("409", new ApiResponse()
                    .description("Complete onboarding before editing the profile").content(problems));
            }
            if (path.equals("/api/v1/learner/onboarding")) {
                item.getPut().setDescription("Completes onboarding once. Later requests return the current profile.");
            }
            if (path.equals("/api/v1/admin/learners/{learnerId}")) {
                item.readOperations().forEach(operation -> operation.getResponses().addApiResponse("404",
                    new ApiResponse().description("Learner profile not found in the directory").content(problems)));
            }
            if (path.endsWith("/avatar")) {
                item.getGet().getResponses().addApiResponse("404", new ApiResponse()
                    .description("Avatar not found").content(problems));
                item.getPut().getResponses().addApiResponse(admin ? "404" : "409", new ApiResponse()
                    .description(admin ? "Learner profile not found in the directory" : "Complete onboarding before uploading an avatar")
                    .content(problems));
            }
        });
    }

    private static void allowLexiconExampleProperties(OpenAPI openApi) {
        Schema<?> sense = openApi.getComponents().getSchemas().get("SenseResponse");
        if (sense == null || sense.getProperties() == null) return;
        Schema<?> examples = sense.getProperties().get("examples");
        if (examples != null && examples.getItems() != null) {
            // Map<String, Object> otherwise constrains every source value to an object in Swagger.
            // Example properties can also be strings, numbers, arrays, booleans or explicit nulls.
            examples.getItems().setAdditionalProperties(true);
        }
    }

    private static void describeLexiconSourcePos(OpenAPI openApi) {
        Schema<?> translation = openApi.getComponents().getSchemas().get("TranslationResponse");
        if (translation == null || translation.getProperties() == null) return;
        Schema<?> sourcePos = translation.getProperties().get("sourcePos");
        if (sourcePos != null) {
            sourcePos.setNullable(true);
            sourcePos.setDescription("Normalized source part of speech, when known; null for legacy records. "
                + "Does not identify a lexical item or sense.");
        }
    }

    /**
     * Registers Lyreo's programmatic component schemas if not already present.
     *
     * <p>Called inside the customizer so schemas and their {@code $ref} consumers
     * exist in the same springdoc lifecycle phase, preventing premature pruning.
     */
    static void ensureProblemSchemas(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        if (components.getSchemas() == null || !components.getSchemas().containsKey(VIOLATION_SCHEMA_NAME)) {
            components.addSchemas(VIOLATION_SCHEMA_NAME, new ObjectSchema()
                .description("Field-level validation error detail")
                .addProperty("field", new StringSchema().description("Name of the invalid field"))
                .addProperty("code", new StringSchema().example("NotBlank")
                    .description("Constraint violation code (e.g., NotBlank, Min, Pattern)"))
                .addProperty("message", new StringSchema()
                    .description("Human-readable violation message")));
        }
        if (components.getSchemas() == null || !components.getSchemas().containsKey(PROBLEM_SCHEMA_NAME)) {
            components.addSchemas(PROBLEM_SCHEMA_NAME, new ObjectSchema()
                .description("RFC 9457 Problem Details error payload with Lyreo extensions")
                .addProperty("type", new StringSchema()
                    .example("urn:lyreo:problem:request-validation-failed")
                    .description("URI reference identifying problem type"))
                .addProperty("title", new StringSchema()
                    .example("Bad Request")
                    .description("Short human-readable summary of problem type"))
                .addProperty("status", new IntegerSchema()
                    .example(400)
                    .description("HTTP status code"))
                .addProperty("detail", new StringSchema()
                    .example("Invalid request content")
                    .description("Human-readable explanation specific to this occurrence"))
                .addProperty("instance", new StringSchema()
                    .example("/api/v1/lessons/build")
                    .description("URI reference identifying specific occurrence"))
                .addProperty("code", new StringSchema()
                    .example("REQUEST_VALIDATION_FAILED")
                    .description("Machine-readable stable error code"))
                .addProperty("correlationId", new StringSchema()
                    .example("req-123e4567-e89b-12d3-a456-426614174000")
                    .description("Correlation identifier for tracing"))
                .addProperty("errors", new ArraySchema()
                    .items(new Schema<>().$ref(VIOLATION_SCHEMA_REF))
                    .description("Field-level validation errors"))
                .addProperty("featureKey", new StringSchema()
                    .description("Entitlement or quota feature key when 403/429 applies"))
                .addProperty("limitType", new StringSchema()
                    .description("Limit type (CAPACITY or PERIOD_QUOTA) when quota exceeded"))
                .addProperty("limit", new IntegerSchema()
                    .description("Configured cap or period quota limit when exceeded"))
                .addProperty("remaining", new IntegerSchema()
                    .description("Remaining quota count at denial time"))
                .addProperty("resetAt", new StringSchema()
                    .description("ISO-8601 UTC timestamp when a period quota resets"))
                .addProperty("currentRevision", new IntegerSchema()
                    .description("Latest server revision when 409 STALE_REVISION occurs"))
                .addProperty("expiredAt", new StringSchema()
                    .description("ISO-8601 UTC timestamp when 409 SESSION_EXPIRED occurs")));
        }
    }

    private static void describePlatformOperations(OpenAPI openApi, Content problems) {
        openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith("/api/v1/learner/entitlements") || path.startsWith("/api/v1/admin/entitlements")) {
                tagWithProblems(item, "Entitlement", problems, path.contains("/admin/"), false);
            } else if (path.startsWith("/api/v1/curriculum") || path.startsWith("/api/v1/admin/curriculum")) {
                tagWithProblems(item, "Curriculum", problems, true, true);
            } else if (path.startsWith("/api/v1/gamification") || path.startsWith("/api/v1/admin/gamification")) {
                tagWithProblems(item, "Gamification", problems, true, path.contains("/diamonds"));
            } else if (path.startsWith("/api/v1/analytics") || path.startsWith("/api/v1/admin/analytics")) {
                tagWithProblems(item, "Analytics", problems, path.contains("/admin/"), false);
            } else if (path.startsWith("/api/v1/chat") || path.startsWith("/api/v1/admin/chat")) {
                tagWithProblems(item, "Chat", problems, false, false);
            } else if (path.startsWith("/api/v1/vocabulary")
                || path.startsWith("/api/v1/lessons")
                || path.startsWith("/api/v1/shadowing-attempts")
                || path.startsWith("/api/v1/grammar")
                || path.startsWith("/api/v1/toeic")
                || path.startsWith("/api/v1/assessment-sessions")) {
                item.readOperations().forEach(operation -> {
                    var responses = operation.getResponses();
                    if (responses != null && !path.startsWith("/api/v1/admin/")) {
                        addIfMissing(responses, "403", "Feature entitlement or role required", problems);
                        addIfMissing(responses, "404", "Resource not found", problems);
                        addIfMissing(responses, "409", "State, revision, expiry, or idempotency conflict", problems);
                    }
                });
            }
        });
    }

    private static void tagWithProblems(
        io.swagger.v3.oas.models.PathItem item,
        String tag,
        Content problems,
        boolean include404,
        boolean include409
    ) {
        item.readOperations().forEach(operation -> {
            operation.setTags(List.of(tag));
            var responses = operation.getResponses();
            if (responses != null) {
                addIfMissing(responses, "403", "Access or entitlement required", problems);
                if (include404) {
                    addIfMissing(responses, "404", "Resource not found", problems);
                }
                if (include409) {
                    addIfMissing(responses, "409", "State or idempotency conflict", problems);
                }
            }
        });
    }

    private static void addIfMissing(ApiResponses responses, String code, String description, Content problems) {
        if (!responses.containsKey(code)) {
            responses.addApiResponse(code, new ApiResponse().description(description).content(problems));
        }
    }
}
