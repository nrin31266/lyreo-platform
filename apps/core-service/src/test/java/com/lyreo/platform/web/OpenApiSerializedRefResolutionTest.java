package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyreo.platform.bootstrap.OpenApiConfiguration;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Serialized-spec regression test for OpenAPI $ref resolution.
 *
 * <p>Serializes the fully-customized OpenAPI spec into JSON (the same format springdoc serves at
 * {@code /v3/api-docs}), then recursively walks every {@code $ref} node and resolves it as a JSON
 * Pointer against the spec root. Any unresolvable pointer is a broken Swagger UI reference.
 *
 * <p>This test catches the lifecycle drift bug where springdoc prunes unreferenced component
 * schemas before the customizer adds {@code $ref} pointers to them.
 */
class OpenApiSerializedRefResolutionTest {

    private JsonNode root;
    private List<String> allRefs;

    @BeforeEach
    void buildAndSerializeSpec() throws Exception {
        // Build OpenAPI through the same lifecycle: bean → customizer
        OpenApiConfiguration config = new OpenApiConfiguration();
        OpenAPI openAPI = config.lyreoOpenAPI();

        // Simulate a realistic set of scanned paths so the customizer has operations to augment
        io.swagger.v3.oas.models.Paths paths = new io.swagger.v3.oas.models.Paths();
        paths.addPathItem("/api/v1/admin/lessons/build",
            new io.swagger.v3.oas.models.PathItem().post(
                new io.swagger.v3.oas.models.Operation()
                    .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                        .addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()))));
        paths.addPathItem("/api/v1/jobs/{id}",
            new io.swagger.v3.oas.models.PathItem().get(
                new io.swagger.v3.oas.models.Operation()
                    .responses(new io.swagger.v3.oas.models.responses.ApiResponses())));
        paths.addPathItem("/api/v1/jobs/{id}/cancel",
            new io.swagger.v3.oas.models.PathItem().post(
                new io.swagger.v3.oas.models.Operation()
                    .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                        .addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse()))));
        paths.addPathItem("/api/v1/me",
            new io.swagger.v3.oas.models.PathItem().get(
                new io.swagger.v3.oas.models.Operation()
                    .responses(new io.swagger.v3.oas.models.responses.ApiResponses())));
        paths.addPathItem("/internal/dev/bootstrap/users",
            new io.swagger.v3.oas.models.PathItem().post(
                new io.swagger.v3.oas.models.Operation()
                    .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                        .addApiResponse("204", new io.swagger.v3.oas.models.responses.ApiResponse()))));
        openAPI.setPaths(paths);

        // Run the customizer (which now registers schemas + adds $refs)
        config.lyreoOpenApiCustomizer().customise(openAPI);

        // Serialize to JSON using swagger-core's own serializer (same as springdoc /v3/api-docs)
        String specJson = Json.pretty(openAPI);
        root = Json.mapper().readTree(specJson);

        // Collect every $ref in the serialized spec
        allRefs = new ArrayList<>();
        collectRefs(root, allRefs);
    }

    /**
     * Core regression test: every local {@code $ref} in the serialized spec resolves.
     */
    @Test
    void allLocalRefsInSerializedSpecAreResolvable() {
        assertThat(allRefs)
            .as("Spec must contain $ref entries (from customizer-added error responses)")
            .isNotEmpty();

        // Resolve each local $ref as a JSON Pointer
        List<String> brokenRefs = new ArrayList<>();
        for (String ref : allRefs) {
            if (!ref.startsWith("#/")) {
                continue;
            }
            String jsonPointer = ref.substring(1); // Remove leading '#'
            JsonNode target = root.at(jsonPointer);
            if (target.isMissingNode()) {
                brokenRefs.add(ref);
            }
        }

        if (!brokenRefs.isEmpty()) {
            fail("Broken local $ref entries in serialized OpenAPI spec:\n  " +
                String.join("\n  ", brokenRefs) +
                "\n\nThis means Swagger UI will show resolver errors. " +
                "Check that OpenApiConfiguration.ensureProblemSchemas() registers all schemas " +
                "referenced by customizer-added $ref pointers.");
        }
    }

    /**
     * Verifies the serialized spec uses {@code LyreoProblemDetail} schema name and that
     * {@code ApiFieldViolation} also exists.
     */
    @Test
    void serializedSpecContainsLyreoProblemDetailAndApiFieldViolation() {
        JsonNode schemas = root.at("/components/schemas");

        assertThat(schemas.has(OpenApiConfiguration.PROBLEM_SCHEMA_NAME))
            .as("Component schema '%s' must exist", OpenApiConfiguration.PROBLEM_SCHEMA_NAME)
            .isTrue();
        assertThat(schemas.has(OpenApiConfiguration.VIOLATION_SCHEMA_NAME))
            .as("Component schema '%s' must exist", OpenApiConfiguration.VIOLATION_SCHEMA_NAME)
            .isTrue();

        // Must NOT have the bare 'ProblemDetail' that collides with Spring's type
        assertThat(schemas.has("ProblemDetail"))
            .as("Bare 'ProblemDetail' schema must not exist to avoid Spring type collision")
            .isFalse();

        // Verify every $ref points to the Lyreo-prefixed name, not the bare name
        for (String ref : allRefs) {
            assertThat(ref)
                .as("$ref must not use bare 'ProblemDetail' name")
                .doesNotEndWith("/ProblemDetail");
        }
    }

    /**
     * Verifies that representative error responses (400, 401, 403, 429) on the lesson-build
     * operation reference {@code LyreoProblemDetail} via {@code application/problem+json}.
     */
    @Test
    void lessonBuildErrorResponsesReferenceLyreoProblemDetail() {
        JsonNode buildPost = root.at("/paths/~1api~1v1~1admin~1lessons~1build/post/responses");
        assertThat(buildPost.isMissingNode())
            .as("POST /api/v1/admin/lessons/build responses must exist")
            .isFalse();

        for (String statusCode : List.of("400", "401", "403")) {
            JsonNode schemaRef = buildPost.at("/" + statusCode +
                "/content/application~1problem+json/schema/$ref");
            assertThat(schemaRef.isMissingNode())
                .as("Response %s must have application/problem+json schema $ref", statusCode)
                .isFalse();
            assertThat(schemaRef.asText())
                .as("Response %s $ref must point to LyreoProblemDetail", statusCode)
                .isEqualTo(OpenApiConfiguration.PROBLEM_SCHEMA_REF);
        }

        // 429 is added by the rate-limit customizer to all /api/ operations
        JsonNode rateLimitRef = buildPost.at("/429/content/application~1problem+json/schema/$ref");
        assertThat(rateLimitRef.isMissingNode())
            .as("Response 429 must have application/problem+json schema $ref")
            .isFalse();
        assertThat(rateLimitRef.asText())
            .as("Response 429 $ref must point to LyreoProblemDetail")
            .isEqualTo(OpenApiConfiguration.PROBLEM_SCHEMA_REF);
    }

    /**
     * Verifies the lesson-build 202 Accepted response with Location header exists.
     */
    @Test
    void lessonBuild202LocationContractExists() {
        JsonNode responses = root.at("/paths/~1api~1v1~1admin~1lessons~1build/post/responses");

        // 200 should have been replaced by 202
        assertThat(responses.has("200"))
            .as("200 response should have been replaced by 202")
            .isFalse();

        JsonNode response202 = responses.get("202");
        assertThat(response202).as("202 response must exist").isNotNull();
        assertThat(response202.at("/headers/Location").isMissingNode())
            .as("202 response must have Location header")
            .isFalse();
        assertThat(response202.at("/headers/Location/schema/type").asText())
            .as("Location header schema type")
            .isEqualTo("string");
    }

    /**
     * Verifies that protected API paths have BearerAuth security and public paths do not.
     */
    @Test
    void securityScopingIsCorrectInSerializedSpec() {
        // Protected endpoint should have BearerAuth security
        JsonNode buildSecurity = root.at(
            "/paths/~1api~1v1~1admin~1lessons~1build/post/security");
        assertThat(buildSecurity.isArray()).isTrue();
        assertThat(buildSecurity.size()).isGreaterThan(0);
        assertThat(buildSecurity.get(0).has(OpenApiConfiguration.BEARER_AUTH)).isTrue();

        // Public endpoint should have empty security
        JsonNode devSecurity = root.at(
            "/paths/~1internal~1dev~1bootstrap~1users/post/security");
        assertThat(devSecurity.isArray()).isTrue();
        assertThat(devSecurity.size())
            .as("Public endpoint must have empty security array")
            .isEqualTo(0);
    }

    /**
     * Recursively collects all {@code $ref} string values from a JSON tree.
     */
    private void collectRefs(JsonNode node, List<String> refs) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode refNode = obj.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                refs.add(refNode.asText());
            }
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                collectRefs(fields.next().getValue(), refs);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectRefs(child, refs);
            }
        }
    }
}
