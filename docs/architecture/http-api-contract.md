# Core HTTP API contract

Core public APIs use versioned `/api/v1/**` paths. Current endpoints and DTO schemas are owned by code and generated OpenAPI. Breaking wire changes require a new path version or an explicit compatibility plan.

## Successful responses

Return typed resources or DTOs directly, without a global success envelope. Use `200` for a successful query/update with body, `201` for synchronous creation, `202` for accepted asynchronous work, and `204` for successful work without body. Async acceptance includes a `Location` pointing to the durable status resource; clients may query that resource even when realtime updates are unavailable.

## Errors

Public failures use RFC 9457 Problem Details with `application/problem+json`. Include safe `type`, `title`, `status`, `detail`, `instance`, stable uppercase `code`, and `correlationId`. Validation failures may include field violations. Never expose stack traces, provider raw errors, credentials, or private storage references. Unexpected infrastructure failures return a safe `500` with `INTERNAL_ERROR`; generic Java exception classes are not globally mapped to client 4xx responses.

Authentication failures return `401` with `AUTHENTICATION_REQUIRED` and the appropriate Bearer challenge. Authenticated requests lacking authority return `403` with `ACCESS_DENIED`. Rate limits return `429` with `RATE_LIMITED` and `Retry-After`. Intentional not-found and state-conflict outcomes use explicit application errors. Bean Validation checks transport shape; domain and application layers enforce business invariants.

## Correlation and DTO boundaries

Accept a bounded, sanitized `X-Correlation-Id` or generate one. Return the same value in the response header and Problem Details and propagate it through logs and downstream work. Clear request-local logging context after each request.

Domain/application models remain HTTP-agnostic. Public transport uses explicit API DTOs and does not expose persistence entities or internal workflow plans. Authorization checks stay server-side.

## OpenAPI ownership

Core generates OpenAPI from the implemented HTTP boundary. Development/test may expose the descriptor and Swagger UI; production disables them by default unless deliberately enabled in a controlled environment. The generated descriptor is the source for current endpoint and schema inventory.
