# Lyreo Operations / Runbook

This document is the operational baseline for the self-host starter. It **does not claim that the
production compose file is an HA production platform**. Public production requires appropriate TLS,
backups/PITR, secret management, monitoring, and infrastructure hardening.

## 1. Runtime components

```text
Edge / TLS terminator
├── Admin Web
├── Core Service
└── Keycloak

Core Service
├── PostgreSQL
├── Cloudflare R2
└── AI Service

AI Service
├── local Qwen runtime (optional GPU)
└── external Groq/Gemini/DeepSeek APIs
```

## 2. Production compose scope

`compose.prod.yml` is a **reproducible self-host/staging baseline** for exercising the full topology.

It does not replace:

- managed PostgreSQL backup/PITR;
- TLS certificate management;
- centralized secret management;
- multi-zone HA;
- log aggregation/APM;
- CDN/domain configuration.

Validate production environment values before deployment with the repository production-config
checks. The self-host/staging baseline is started with:

```bash
docker compose \
  --env-file infra/docker/.env \
  -f compose.prod.yml \
  up -d --build
```

For the optional GPU runtime override:

```bash
docker compose \
  --env-file infra/docker/.env \
  -f compose.prod.yml \
  -f compose.gpu.yml \
  up -d --build
```

Additional container-specific notes live in `../infra/docker/README.md`.

## 3. Database ownership and backup

PostgreSQL contains authoritative operational state including:

- user/product state;
- job state/cancellation;
- normalized lesson/TOEIC/lexicon data;
- Diamond ledger;
- analytics projections;
- Spring Modulith event-publication registry.

Before a production migration:

1. complete a database backup;
2. run the Flyway migration in staging;
3. inspect destructive or long-lock SQL;
4. verify Core startup/schema validation;
5. promote only after those checks succeed.

Large import runs should retain checksum/dataset-version metadata so reruns remain controlled.

## 4. R2 ownership

R2 stores large artifacts/files such as lesson media, lexicon media, TOEIC media, learner speech
artifacts, and job artifacts. Canonical storage authority rules are defined in `../AGENTS.md`; object
layout intent is documented in `LYREO_PLATFORM_SPEC.md` §28–29.

Use separate buckets per environment, for example:

```text
lyreo-dev
lyreo-staging
lyreo-prod
```

Never allow staging credentials to write to the production bucket.

## 5. Background Job Runbook

### 5.1 Job states

Current operational states:

```text
QUEUED
RUNNING
RETRY_WAIT
CANCEL_REQUESTED
CANCELLED
SUCCEEDED
FAILED
```

A `RUNNING` job carries lease/heartbeat information. The authoritative queue/cancellation/fencing
rules are defined in `../AGENTS.md` §7; this section describes how to operate those states.

### 5.2 Cancel

Use the supported cancellation API/UI so cancellation becomes durable job state. Long-running
handlers observe cancellation between expensive steps and before committing expensive external
outputs.

Do not replace cancellation with an operational cache flag.

### 5.3 Stale RUNNING job

When a worker process dies:

1. heartbeat stops;
2. its lease expires;
3. the recovery scheduler makes the job claimable again;
4. a new worker resumes from persisted lesson-build step state;
5. idempotent steps skip already completed durable output.

Do not mass-update `RUNNING → QUEUED` by hand without first verifying lease/fencing semantics.

### 5.4 Retry

The starter uses capped backoff and eventually marks work `FAILED` after the configured attempt
limit.

Classify failures before changing retry behavior: invalid/non-retryable input must not be retried
blindly, while transient provider/network failures may be retryable. Review the job/AI exception
policy before enabling production changes because unnecessary retries increase latency and provider
cost.

## 6. AI provider incident

Queued/retry jobs do **not** permanently pin a provider. `provider_snapshot_json` records the route
context when the job was accepted, while each execution resolves the currently enabled route and
records the actual provider/model in `ai_invocation`.

When a provider is failing:

1. disable/change the route or fallback in Admin Settings;
2. inspect `ai_invocation` latency/status/error;
3. use retained raw artifacts only for audit/debug, not workflow state;
4. rotate the provider credential if compromise is suspected.

Do not modify business prompts in FastAPI as an incident workaround; the AI responsibility boundary
is owned by `../AGENTS.md` §8.

`MASTER_ENCRYPTION_KEY` must not be casually rotated because stored provider credentials are encrypted
with it. Production rotation requires a deliberate re-encryption procedure.

## 7. Keycloak incident

Keycloak is the identity/role authority. Core stores the Keycloak subject and product/profile state,
not an independent password system.

Back up the Keycloak database separately. A restore must preserve realm/client identifiers and
secrets consistently with Core/frontend configuration.

Development bootstrap scripts are for controlled dev/staging setup. Production user creation occurs
through normal authentication/JIT provisioning rather than seeded example passwords.

## 8. Cache behavior

Caffeine is local/disposable cache:

- cache loss on restart is expected;
- it must not hold durable business/job state;
- with multiple Core instances, entries can be temporarily different until TTL/refresh behavior
  converges.

Any move to globally consistent distributed cache/quota requires an explicit architecture decision.

## 9. Rate limiting

Inbound rate limiting currently uses local Bucket4j token buckets. With multiple Core instances,
that quota is not globally shared; any distributed/global quota design must be benchmarked and
explicitly decided.

Outbound dependency protection uses Resilience4j policies such as retry, circuit breaker, bulkhead,
and timeout according to dependency/capability needs.

## 10. Observability baseline

Core exposes:

```text
/actuator/health
/actuator/info
/actuator/metrics
```

Correlation IDs help trace requests through logs, while job/AI persistence provides an operational
audit trail.

Before public production, add as appropriate:

- structured JSON logging;
- centralized logs;
- metrics scraping/dashboard;
- alerts for failed jobs, provider error rate, database connection saturation, and object-storage
  failures;
- exception tracking.

## 11. Data retention

Do not keep learner audio indefinitely merely because object storage is inexpensive. Before public
production, define retention for:

- speech recordings;
- raw AI request/response artifacts;
- chat history;
- deleted-account purge;
- audit data.

Raw artifacts may contain learner content and therefore require corresponding privacy/retention
policy.

## 12. Deployment checklist

Before release:

- [ ] production `.env` files contain no placeholders;
- [ ] no secret is committed;
- [ ] Keycloak issuer/redirect URIs match the public deployment;
- [ ] database backup completed;
- [ ] Flyway validation passed;
- [ ] Java/Python/frontend tests passed;
- [ ] R2 bucket/credentials target the correct environment;
- [ ] Core↔AI internal token matches on both sides;
- [ ] CORS contains only intended origins;
- [ ] development bootstrap endpoint is disabled;
- [ ] ADMIN + LEARNER login smoke tests passed;
- [ ] lesson build/cancel/retry smoke tests passed;
- [ ] Diamond ledger idempotency was verified;
- [ ] mobile audio/recording smoke test passed;
- [ ] any remaining production limitation is tracked outside the temporary starter handoff.

## 13. Starter maturity / production-hardening backlog

These boundaries are intentionally replaceable, but **must not be interpreted as production
certification**:

- Vocabulary scheduling currently uses `StarterFsrsCompatibleScheduler`; validate/benchmark the
  chosen production scheduler and migration strategy before relying on it for long-lived learner
  state.
- Speech Tier-1 scoring is a deterministic transcript/timing baseline, not a claim of phoneme/prosody
  scientific accuracy. Tier-2 multimodal judging is optional capability work.
- Caffeine/Bucket4j state is local to each Core instance; global multi-instance behavior is a
  separate scaling decision.
- Notification realtime currently uses an in-memory SSE feed; multi-instance fan-out requires a
  deliberate shared-transport design rather than an incidental broker/cache addition.
- `compose.prod.yml` is a reproducible self-host/staging topology; HA/TLS/PITR/secret manager/
  centralized logging remain production-hardening work.
- Live provider contract/model quality/cost must be smoke-tested with real credentials before a
  production route is enabled.
- YouTube media processing follows the current product assumption; legal/policy hardening must stay
  separate from Lesson domain design.
- Recommendation/advanced analytics require real learner history before tuning.
- Chat has a real module boundary but remains P3 and must not displace Lesson/TOEIC/Vocabulary/
  Curriculum priorities.
