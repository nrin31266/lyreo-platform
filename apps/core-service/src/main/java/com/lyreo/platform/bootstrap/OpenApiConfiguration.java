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
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.0 configuration for Springdoc.
 *
 * Configures the Bearer JWT security scheme, standard RFC 9457 Problem Details schema,
 * high-level domain tags, and operation-level contracts.
 */
@Configuration
public class OpenApiConfiguration {

    public static final String BEARER_AUTH = "BearerAuth";

    @Bean
    public OpenAPI lyreoOpenAPI() {
        Schema<?> fieldViolationSchema = new ObjectSchema()
            .name("ApiFieldViolation")
            .description("Field-level validation error detail")
            .addProperty("field", new StringSchema().description("Name of the invalid field"))
            .addProperty("code", new StringSchema().example("NotBlank").description("Constraint violation code (e.g., NotBlank, Min, Pattern)"))
            .addProperty("message", new StringSchema().description("Human-readable violation message"));

        Schema<?> problemDetailSchema = new ObjectSchema()
            .name("ProblemDetail")
            .description("RFC 9457 Problem Details error payload")
            .addProperty("type", new StringSchema().example("urn:lyreo:problem:request-validation-failed").description("URI reference identifying problem type"))
            .addProperty("title", new StringSchema().example("Bad Request").description("Short human-readable summary of problem type"))
            .addProperty("status", new IntegerSchema().example(400).description("HTTP status code"))
            .addProperty("detail", new StringSchema().example("Invalid request content").description("Human-readable explanation specific to this occurrence"))
            .addProperty("instance", new StringSchema().example("/api/v1/lessons/build").description("URI reference identifying specific occurrence"))
            .addProperty("code", new StringSchema().example("REQUEST_VALIDATION_FAILED").description("Machine-readable stable error code"))
            .addProperty("correlationId", new StringSchema().example("req-123e4567-e89b-12d3-a456-426614174000").description("Correlation identifier for tracing"))
            .addProperty("errors", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/ApiFieldViolation")).description("Field-level validation errors"));

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
                .addSecuritySchemes(BEARER_AUTH, securityScheme)
                .addSchemas("ApiFieldViolation", fieldViolationSchema)
                .addSchemas("ProblemDetail", problemDetailSchema))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
            .tags(List.of(
                new Tag().name("Identity").description("User identity and session endpoints"),
                new Tag().name("Learner").description("Learner profile, onboarding and preferences"),
                new Tag().name("Lesson").description("Admin lesson authoring and build triggers"),
                new Tag().name("Lesson Practice").description("Learner lesson dictation and practice"),
                new Tag().name("Jobs").description("Background job tracking and cancellation"),
                new Tag().name("AI Administration").description("AI provider and route routing management"),
                new Tag().name("Lexicon").description("Global learner dictionary lookup"),
                new Tag().name("Vocabulary").description("Learner vocabulary SRS and review"),
                new Tag().name("Grammar").description("Grammar bank questions and practice"),
                new Tag().name("TOEIC").description("TOEIC test attempts and submission"),
                new Tag().name("Chat").description("English tutor conversation"),
                new Tag().name("Realtime").description("Admin realtime SSE feeds")
            ));
    }

    @Bean
    public OpenApiCustomizer lyreoOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            var problemRef = new Schema<>().$ref("#/components/schemas/ProblemDetail");
            var problemContent = new Content().addMediaType(
                "application/problem+json",
                new MediaType().schema(problemRef)
            );

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
}
