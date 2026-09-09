# FEAT-AI-ROUTING — AI Provider Routing and Audit

Requirements: [AI](../requirements/ai.md); story [US-AI-001/002](../requirements/stories/ai.md).
Configuration semantics: [Admin runtime policy](../CONFIGURATION.md#3-admin-runtime-policy).

## Admin flow

Admin manages provider/model strings, enabled state, priority/fallback and policy per capability.
Credential write reaches Core over protected API, is AES-GCM encrypted with environment master key,
and read responses expose only metadata. UI never receives plaintext provider keys.

## Execution flow

1. Java workflow supplies business-built input/prompt and expected schema.
2. At acceptance, routing snapshot records context for audit.
3. At execution, `AiRoutingSnapshotService`/routing repository resolves currently enabled route.
4. `AiInvocationService` records attempt and calls `FastApiAiExecutionGateway`.
5. FastAPI authenticates internal token, dispatches capability/provider/runtime and normalizes output.
6. Core records actual provider/model/status/timing/artifact keys and applies business transition.

Queued/retry work does not permanently pin provider. The snapshot cannot reproduce stochastic output;
actual invocation is the audit fact. Provider fallback/timeout/retry must classify errors and respect
job cancellation.

## Contracts and evidence

Java code: `modules/ai/src/main/java/com/lyreo/ai/`. FastAPI wire models:
`services/ai-service/app/schemas.py`; endpoints in `app/main.py`; contract tests in
`services/ai-service/tests/test_api.py`. Mock success proves schema/path, not model quality/cost.
