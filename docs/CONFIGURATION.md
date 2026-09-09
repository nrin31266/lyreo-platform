# Lyreo Configuration Model

This document defines **where configuration lives, who may change it, which values are secrets,
and how precedence works**. The goal is to avoid two extremes:

1. hard-coding so much product behavior that every change requires code changes; and
2. turning every domain invariant into an uncontrolled `settings(key,value)` table.

## 1. General principles

Lyreo has five configuration layers:

```text
Deployment capability / secret
        ↓
Admin runtime policy
        ↓
Lesson build snapshot
        ↓
Learner persistent preference
        ↓
Current session override
```

A lower layer **must not** override a safety/capability limit imposed by a higher layer.

Examples:

- Admin sets `sentence IPA = DISABLED` → a learner cannot force the server to generate IPA even if
  their personal preference is `ALWAYS`.
- Admin allows `ON_DEMAND` → the learner may choose `OFF`, `TAP_TO_SHOW`, or `ALWAYS`.
- A session may temporarily change playback speed without changing the cross-device default unless
  the learner explicitly saves it as a persistent preference.

## 2. Deployment configuration (`.env` / secret manager)

These values are required for a process to **start or connect to infrastructure**. They are not
edited through the Admin UI.

| Group | Example | Dev file | May be exposed to frontend? |
|---|---|---|---|
| Database | JDBC URL/user/password | `apps/core-service/.env` | No |
| Keycloak server/bootstrap | admin password, confidential client secret | `infra/docker/.env`, `infra/keycloak/.env` | No |
| OIDC public client metadata | issuer URL, public client ID | frontend `.env` | Yes |
| R2 | endpoint/access key/secret | `apps/core-service/.env` | No |
| AI internal auth | Core↔FastAPI token | Core + AI `.env` | No |
| Encryption root key | `MASTER_ENCRYPTION_KEY` | Core `.env` | No |
| Runtime | Qwen model/device/dtype | AI `.env` | No |
| Data import | dataset path/source/checksum, importer DB/R2 settings | `tools/data-import/.env` | No |

### 2.1 No root `.env`

Each executable owns its own environment:

```text
infra/docker/.env             Docker infrastructure interpolation
infra/keycloak/.env           local bootstrap scripts
apps/core-service/.env        Spring Boot runtime
services/ai-service/.env      FastAPI/model runtime
apps/admin-web/.env           browser-public build/dev config
apps/mobile/.env              EXPO_PUBLIC_* only
tools/data-import/.env        importer DB/R2/data paths
```

`./scripts/init-dev-env.sh` copies the `.env.example` files and synchronizes local secrets that must
match across processes.

A variable name may appear in more than one owner only when both processes genuinely consume the
same boundary value (for example Core↔AI internal token, importer/Core R2 settings, or Keycloak
bootstrap credentials). The repository validator rejects new cross-owner duplicate env keys unless
they are explicitly allowlisted as a reviewed boundary.

### 2.2 Frontend variables are public

Any variable beginning with `VITE_` or `EXPO_PUBLIC_` must be treated as **public data**. Do not put
these values there:

- Gemini/Groq/DeepSeek API keys;
- R2 secrets;
- Keycloak confidential client secrets;
- database passwords;
- `MASTER_ENCRYPTION_KEY`.

The repository validator fails if frontend `.env.example` files contain names that look like
secret/password/API-key variables.

### 2.3 Grammar/TOEIC dataset bootstrap

`tools/data-import/.env` owns the external dataset bootstrap settings:

```dotenv
DAUTOEIC_DATA_DIR=../../.data/datasets/toeic
DAUTOEIC_DATA_URL=https://drive.google.com/file/d/1FQgEswv3hUmT0Wv8Tyy9Jl_p9iLfoLZs/view?usp=sharing
DAUTOEIC_DATA_SHA256=
```

Relative `DAUTOEIC_DATA_DIR` values are resolved from `tools/data-import/`. `DAUTOEIC_DATA_URL` is
used only by the developer/bootstrap helper when the local dataset is missing. The checksum is
optional until the team records the canonical archive SHA-256; once populated, a mismatch is a hard
bootstrap failure. Raw data remains outside Git. Exact importer-facing dataset semantics live in
`DATA_PIPELINES.md`.

## 3. Admin runtime policy

Values an administrator should be able to change while the system is running include:

- provider/model routing;
- enabling/disabling an AI provider;
- default lesson-processing policy;
- sentence IPA strategy (`DISABLED`, `ON_DEMAND`, `PREGENERATE`);
- default accent;
- feature availability;
- rate/cost policy for expensive AI capabilities;
- controlled mission/reward configuration.

### 3.1 Do not hard-code model names

Java business logic uses capabilities:

```text
STT
ALIGNMENT
GENERAL_LLM
REASONING_LLM
TTS
PRONUNCIATION_JUDGE
```

`ai_capability_route` selects provider + model + fallback. Model identifiers change frequently, so
model names are runtime strings rather than Java enums.

### 3.2 Provider API keys

`ai_provider.encrypted_api_key` stores AES-GCM ciphertext. `MASTER_ENCRYPTION_KEY` exists only
outside the database.

Admin APIs return metadata such as:

```json
{
  "configured": true,
  "last4": "91F2"
}
```

There is no API that reads a provider key back as plaintext.

## 4. Lesson build snapshot

When an administrator creates a lesson, the accepted build configuration is snapshotted into
`background_job.config_snapshot_json` and routing metadata into
`lesson_build_job.provider_snapshot_json`.

Example:

```json
{
  "activities": ["DICTATION", "SHADOWING"],
  "annotations": {
    "translation": true,
    "lexical": true,
    "grammar": true,
    "sentenceIpa": false,
    "thoughtGroups": true
  },
  "providers": {
    "stt": "QWEN3_ASR",
    "alignment": "QWEN3_FORCED_ALIGNER",
    "llm": "GROQ"
  }
}
```

Snapshot goals:

- preserve which routes were configured when the job was accepted;
- keep build intent/options/step plan durable even if Admin settings later change;
- retain the initial routing context for audit/debug alongside the actual `ai_invocation` records.

**Important:** `provider_snapshot_json` is currently audit metadata; it does **not** pin execution.
Each AI call resolves the currently enabled route at execution time so operators can disable a
broken provider or change a fallback for queued/retry jobs. `ai_invocation` records the provider
and model actually used. Pinning provider/model execution to the snapshot would require a separate
architecture decision.

## 5. Learner persistent preferences

Cross-device preferences are stored in `learner_profile.preferences_json` and deserialized into the
typed `LearnerPreferences` object.

Current preference fields include:

- preferred accent;
- translation display timing;
- sentence IPA display timing;
- vocabulary/grammar note timing;
- thought-group/karaoke highlighting;
- proper-noun hints;
- default playback speed.

API:

```text
GET /api/v1/learner/preferences
PUT /api/v1/learner/preferences
```

A learner preference does **not** grant a server capability. It only controls how the learner wants
to experience data that the higher configuration layers permit.

## 6. Session override

State that only matters during the current study session stays in React Native local state/store,
for example:

- temporary `0.8x` speed;
- current sentence loop;
- whether the IPA panel is open;
- current sentence index;
- animation state.

Do not PUT every UI tap to the backend. Persist only when the learner changes a long-lived
preference or when business behavior requires storing progress/attempt state.

### 6.1 Device-local application preferences

Locale and visual theme are currently device/browser preferences, not learner-domain state:

```text
Admin Web  → localStorage
Mobile     → AsyncStorage
```

Mobile detects the OS locale with `expo-localization` when no explicit locale has been saved. Theme
preference is `system | light | dark`, with `system` as the default. These values are not secrets and
therefore must not be stored in SecureStore. Learning preferences that affect cross-device study
behavior continue to use the learner preference API described in
[Learner persistent preferences](#5-learner-persistent-preferences).

Theme/locale are also **not deployment environment variables**. Admin persists them in browser
`localStorage`; Mobile persists them in AsyncStorage. Deployment env controls capability/endpoints,
not an individual user's visual or language preference.

Dark-mode resolution is platform-owned:

```text
Admin:  localStorage preference → prefers-color-scheme (when system) → .dark → semantic CSS vars
Mobile: AsyncStorage preference  → useColorScheme()      (when system) → semanticThemes → NativeWind vars
```

Both adapters consume `@lyreo/design-system`; neither maintains an independent color palette.

## 7. Storage configuration

### Local dev

```dotenv
STORAGE_MODE=local
LOCAL_STORAGE_ROOT=../../.data/storage
```

This is the default development mode because a cloned repository can run without R2 credentials.

### R2

```dotenv
STORAGE_MODE=r2
R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...
R2_BUCKET=lyreo-dev
R2_REGION=auto
```

The database stores **object keys**, not signed URLs. Download URLs are generated on demand with a
short TTL.

`STORAGE_MODE` changes only where artifact bytes are persisted. AI token/inference cost is tied to
capability/provider invocations (for example LLM/TTS/STT calls), not to whether the resulting bytes
are stored locally or in R2.

## 8. Background job tuning

Core environment variables:

```dotenv
JOB_POLL_INTERVAL_MS=1000
JOB_RECOVER_INTERVAL_MS=30000
JOB_LEASE_SECONDS=60
JOB_CLAIM_BATCH_SIZE=5
```

Operational constraints:

- the lease must be longer than normal heartbeat jitter;
- worker heartbeats are independent from UI progress callbacks;
- a step must be idempotent or protected by persisted step state;
- cancellation is durable database state, not a cache flag.

Do not increase `JOB_CLAIM_BATCH_SIZE` merely to make the system “faster”; the starter worker
processes a claimed batch sequentially. Scale through additional Core instances or a bounded
executor only after measurement.

## 9. Keycloak configuration

Development uses two groups of variables:

- `infra/docker/.env`: container bootstrap + database;
- `infra/keycloak/.env`: seed/verification scripts.

`init-dev-env.sh` synchronizes the confidential `lyreo-core-service` client secret between the
Docker and Keycloak bootstrap envs. Core runtime does not receive that secret until code actually
implements a Keycloak Admin API client.

Frontend applications receive only realm URL + public client ID. Mobile/Admin use Authorization
Code + PKCE.

## 10. What must stay in code?

Not everything is configurable. These invariants belong in code/schema:

- score/Diamond authority must not come from the client;
- Diamond ledger operations must be idempotent;
- a module must not read another module's repository;
- Flyway owns schema evolution;
- job fencing/lease semantics;
- authorization role checks;
- valid domain transitions;
- signed URLs are not persisted as canonical storage references.

If a change weakens an invariant, make an architecture decision rather than adding a checkbox.

## 11. Checklist when adding configuration

Before adding a setting, ask:

1. Is this a deployment secret, admin policy, learner preference, or session state?
2. Does it need audit/version/snapshot history?
3. Does it need to work cross-device?
4. Is the value secret?
5. May a lower layer override it?
6. Is there a safe default?
7. Does a cache need invalidation?
8. Does it require a migration, or is it a backward-compatible JSON field?
9. Do the owning `.env.example` and developer docs explain any non-obvious variable?
10. Does this accidentally turn a domain invariant into configuration?
