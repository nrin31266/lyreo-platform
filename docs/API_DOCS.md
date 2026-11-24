# Core API documentation

Core exposes the generated OpenAPI descriptor and Swagger UI from the Spring Boot service.
With the local `dev` profile, use these URLs:

- [Swagger UI](http://localhost:8080/swagger-ui.html)
- [Swagger UI direct path](http://localhost:8080/swagger-ui/index.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)

The local `dev` profile enables both endpoints. The default configuration disables them, so a
non-development environment must explicitly set `SPRINGDOC_API_DOCS_ENABLED=true` and
`SPRINGDOC_SWAGGER_UI_ENABLED=true` when exposing the documentation is intentional.

`/api/docs` is not a Core route. Swagger UI is public for discovery, while protected API operations
still require the bearer token expected by the selected endpoint.
