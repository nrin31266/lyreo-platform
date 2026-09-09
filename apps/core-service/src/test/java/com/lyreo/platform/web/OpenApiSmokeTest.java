package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.platform.bootstrap.OpenApiConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.junit.jupiter.api.Test;

class OpenApiSmokeTest {

    @Test
    void openApiConfigurationBuildsValidSpecification() {
        OpenApiConfiguration config = new OpenApiConfiguration();
        OpenAPI openAPI = config.lyreoOpenAPI();

        assertThat(openAPI).isNotNull();
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Lyreo Core API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.0.0");

        // Security scheme
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey(OpenApiConfiguration.BEARER_AUTH);
        SecurityScheme scheme = openAPI.getComponents().getSecuritySchemes().get(OpenApiConfiguration.BEARER_AUTH);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");

        // Global security item
        assertThat(openAPI.getSecurity()).singleElement()
            .satisfies(req -> assertThat(req.containsKey(OpenApiConfiguration.BEARER_AUTH)).isTrue());

        // Standard error schemas
        assertThat(openAPI.getComponents().getSchemas()).containsKeys("ProblemDetail", "ApiFieldViolation");
        var violationSchema = openAPI.getComponents().getSchemas().get("ApiFieldViolation");
        assertThat(violationSchema.getProperties()).containsKeys("field", "code", "message");
        assertThat(violationSchema.getProperties()).doesNotContainKey("rejectedValue");

        var problemSchema = openAPI.getComponents().getSchemas().get("ProblemDetail");
        assertThat(problemSchema.getProperties()).containsKeys(
            "type", "title", "status", "detail", "instance", "code", "correlationId", "errors"
        );

        // Tags
        assertThat(openAPI.getTags()).extracting(Tag::getName).contains(
            "Identity",
            "Learner",
            "Lesson",
            "Lesson Practice",
            "Jobs",
            "AI Administration",
            "Lexicon",
            "Vocabulary",
            "Grammar",
            "TOEIC",
            "Chat",
            "Realtime"
        );
    }

    @Test
    void customizerEnforcesOperationLevelContractsAndRateLimit429() {
        OpenApiConfiguration config = new OpenApiConfiguration();
        OpenAPI openAPI = config.lyreoOpenAPI();

        // Simulate scanned paths before customizer runs
        io.swagger.v3.oas.models.Paths paths = new io.swagger.v3.oas.models.Paths();
        io.swagger.v3.oas.models.Operation buildPost = new io.swagger.v3.oas.models.Operation()
            .responses(new io.swagger.v3.oas.models.responses.ApiResponses().addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()));
        paths.addPathItem("/api/v1/admin/lessons/build", new io.swagger.v3.oas.models.PathItem().post(buildPost));

        io.swagger.v3.oas.models.Operation jobGet = new io.swagger.v3.oas.models.Operation()
            .responses(new io.swagger.v3.oas.models.responses.ApiResponses());
        paths.addPathItem("/api/v1/jobs/{id}", new io.swagger.v3.oas.models.PathItem().get(jobGet));

        io.swagger.v3.oas.models.Operation devBootstrapPost = new io.swagger.v3.oas.models.Operation()
            .responses(new io.swagger.v3.oas.models.responses.ApiResponses().addApiResponse("204", new io.swagger.v3.oas.models.responses.ApiResponse()));
        paths.addPathItem("/internal/dev/bootstrap/users", new io.swagger.v3.oas.models.PathItem().post(devBootstrapPost));
        openAPI.setPaths(paths);

        // Run customizer
        config.lyreoOpenApiCustomizer().customise(openAPI);

        // Verify /api/v1/admin/lessons/build operation contracts
        var lessonBuildOp = openAPI.getPaths().get("/api/v1/admin/lessons/build").getPost();
        assertThat(lessonBuildOp.getResponses()).doesNotContainKey("200");
        assertThat(lessonBuildOp.getResponses()).containsKey("202");
        assertThat(lessonBuildOp.getResponses().get("202").getHeaders()).containsKey("Location");

        assertThat(lessonBuildOp.getResponses()).containsKey("400");
        assertThat(lessonBuildOp.getResponses().get("400").getContent()).containsKey("application/problem+json");

        assertThat(lessonBuildOp.getResponses()).containsKey("401");
        assertThat(lessonBuildOp.getResponses().get("401").getContent()).containsKey("application/problem+json");

        assertThat(lessonBuildOp.getResponses()).containsKey("403");
        assertThat(lessonBuildOp.getResponses().get("403").getContent()).containsKey("application/problem+json");

        // Verify protected operation requires BearerAuth and documents 429 RATE_LIMITED
        assertThat(lessonBuildOp.getSecurity()).singleElement()
            .satisfies(req -> assertThat(req.containsKey(OpenApiConfiguration.BEARER_AUTH)).isTrue());
        assertThat(lessonBuildOp.getResponses()).containsKey("429");
        assertThat(lessonBuildOp.getResponses().get("429").getHeaders()).containsKey("Retry-After");
        assertThat(lessonBuildOp.getResponses().get("429").getContent()).containsKey("application/problem+json");

        var jobGetOp = openAPI.getPaths().get("/api/v1/jobs/{id}").getGet();
        assertThat(jobGetOp.getSecurity()).singleElement()
            .satisfies(req -> assertThat(req.containsKey(OpenApiConfiguration.BEARER_AUTH)).isTrue());
        assertThat(jobGetOp.getResponses()).containsKey("429");
        assertThat(jobGetOp.getResponses().get("429").getHeaders()).containsKey("Retry-After");

        // Verify public dev endpoint has empty security and no 429
        var devBootstrapOp = openAPI.getPaths().get("/internal/dev/bootstrap/users").getPost();
        assertThat(devBootstrapOp.getSecurity()).isEmpty();
        assertThat(devBootstrapOp.getResponses()).doesNotContainKey("429");
    }
}
