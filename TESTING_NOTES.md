# TEMPORARY — Lyreo artifact verification handoff

> **Tệp bàn giao tạm thời, không phải source of truth của dự án.**
> File này chỉ ghi kết quả audit/test của artifact giao ngày **2026-09-07** và những gate còn phải
> chạy trên máy dev/CI có đúng toolchain + network. Quy tắc kỹ thuật thuộc `AGENTS.md`; setup thuộc
> `README.md`; kiến trúc/spec thuộc `docs/*`. Có thể xóa file này sau khi CI baseline xanh.

## 1. Phạm vi audit cuối

Pass cuối **không thay đổi business logic Java/Python**. Chỉ rà/sửa:

- frontend foundation Admin/Mobile;
- semantic Design System + Dark Mode;
- shared EN/VI i18n;
- Expo/NativeWind/RNR/Tailwind configuration;
- frontend workspace/dependency packaging;
- `.env.example` ownership + setup helpers;
- external TOEIC/Grammar data bootstrap;
- repository validator/guardrails;
- documentation ownership/consistency;
- artifact cleanliness/ZIP metadata.

## 2. Toolchain quan sát trong sandbox

```text
Java       21.0.11        (repo target 25)
Node       v22.16.0       (repo target 24.20.0)
Python     3.13.5         (project target 3.12)
uv         0.10.0
TypeScript 5.8.3 global   (workspace manifests declare 5.9.x)
Docker     unavailable
pnpm       unavailable; Corepack cannot reach npm registry from sandbox
Maven      wrapper cannot reach Maven Central from sandbox
```

Repository toolchain source of truth:

```text
.java-version  -> 25
.nvmrc         -> 24.20.0
mise.toml      -> Java 25 / Node 24.20.0 / Python 3.12
packageManager -> pnpm@12.3.1
```

## 3. PASS — repository/static guardrails

Final command before packaging:

```bash
make validate
```

Result:

```text
VALIDATION OK | Java=208 Python=21 TS/TSX=45 SQL=8
WARNING: pnpm-lock.yaml is absent; generate it after `pnpm install` on a networked dev machine
```

`make validate` currently covers, among other invariants:

- required repo/docs/config/bootstrap files;
- no committed real `.env`, `.data`, `node_modules`, `.venv`, caches or build output;
- agent aliases must remain symlinks to `AGENTS.md`;
- Kafka/Redis prohibition and core architecture boundaries;
- FastAPI must not become a business persistence/orchestration backend;
- frontend public env must not contain secret-looking keys;
- env keys must have an owner-scope consumer;
- duplicate env keys are rejected unless explicitly reviewed as a real cross-process boundary;
- Mobile SecureStore is auth/session-only;
- Admin/Mobile app source cannot contain raw hex/RGB colors;
- feature code cannot consume primitive palette names directly;
- EN/VI namespace key parity and literal translation-key references;
- `ThemeColors` ↔ `semanticThemes.light` ↔ `semanticThemes.dark` 100% role parity;
- semantic theme values must resolve to existing `primitiveColors` keys and cannot be `undefined/null`;
- Admin semantic role -> CSS variable mappings;
- Mobile semantic role -> NativeWind `vars()` -> Tailwind mappings;
- Web `system` theme flow keeps `prefers-color-scheme`, change listener and `.dark` class wiring;
- Mobile `system` theme flow keeps `useColorScheme()`, `userInterfaceStyle=automatic`, StatusBar and navigation background wiring;
- NativeWind/RNR baseline config (`tailwindcss-animate`, `PortalHost`, `inlineRem=16`, no custom Metro resolver hack);
- reviewed Expo Router/OAuth direct dependency pins;
- shared non-color foundation token wiring;
- Admin Docker build context must copy every `workspace:*` package Admin depends on;
- stale/root-only `pnpm-lock.yaml` is rejected if one exists.

Negative mutation probes were also run against a temporary copy and correctly rejected:

```text
raw color inserted into Mobile screen            REJECTED
missing role in semanticThemes.dark              REJECTED
Mobile --background wired to colors.foreground  REJECTED
```

## 4. PASS — Dark Mode static verification

`ThemeColors` currently has **18 semantic color roles**. Both theme maps contain exactly those roles,
with no missing/extra/duplicate key and no null/undefined assignment.

Web flow verified statically:

```text
localStorage preference
  -> system ? matchMedia('(prefers-color-scheme: dark)') : explicit mode
  -> media change listener
  -> semanticThemes[resolvedMode]
  -> CSS variables
  -> document.documentElement.classList.toggle('dark', ...)
  -> Tailwind v4 / repo-owned Radix-style UI
```

Mobile flow verified statically:

```text
AsyncStorage preference
  -> system ? React Native useColorScheme() : explicit mode
  -> semanticThemes[resolvedMode]
  -> NativeWind vars() on root View
  -> semantic classes on screens/components
```

`app.json` keeps `userInterfaceStyle: "automatic"`; resolved mode controls Expo StatusBar and Stack
content background. No app screen currently contains raw theme/brand color literals.

## 5. PASS — frontend foundation/dependency/config audit

### Admin

Confirmed consumers for both shared packages:

```text
@lyreo/design-system
@lyreo/i18n
```

Tailwind v4/Radix-style repo-owned primitives are exercised by real starter screens rather than a
throwaway demo page. Current primitives include Button/Card/Dialog/Input/Select/Switch/Textarea.
Legacy global CSS rules that could override these primitives were removed earlier in the audit.

A clean-container packaging bug was found and fixed: `apps/admin-web/Dockerfile` previously copied
`packages/design-system` but omitted `packages/i18n`, even though Admin declares `@lyreo/i18n` as a
`workspace:*` dependency. Dockerfile now copies both shared packages, and validator protects this
workspace-package build boundary.

### Mobile

Confirmed:

- Expo SDK 57 / React Native 0.86.3 / React 19.2.3 baseline;
- NativeWind v4 + Tailwind 3.4 toolchain;
- `tailwindcss-animate` + `@rn-primitives/portal` RNR baseline;
- no custom Metro resolver hack;
- `@lyreo/design-system/foundation` public subpath is used for shared radius tokens;
- repo-owned Button/Card/Text primitives have consumers;
- unused repo-owned Input primitive was removed rather than kept as dead starter code;
- `expo-localization` initializes i18next from device locale before saved preference override;
- locale/theme use AsyncStorage; SecureStore remains auth/session-only.

Expo dependency corrections made during audit:

```text
expo-constants                 ~57.0.17  direct Expo Router dependency
expo-linking                   ~57.0.9   direct Expo Router dependency
expo-status-bar                ~57.0.1
react-native-safe-area-context ~5.7.0
react-native-screens           ~4.26.0
expo-auth-session              ~57.0.11
expo-crypto                    ~57.0.2   required alongside AuthSession
babel-preset-expo              ~57.0.10
```

`expo-audio` was removed because current source does not consume it. `expo-dev-client`, Reanimated,
Worklets, Safe Area, Screens, Linking and Constants can have no direct source import while still
being intentional framework/native/router dependencies, so they are not treated as dead packages.

Offline source checks:

```text
TypeScript/TSX parse OK                    files=43
Local frontend relative imports resolve   imports=81
Design-system local tsc                    PASS
Shared i18n local tsc                      PASS
Admin runtime import graph                 20/21 reachable; only vite.config.ts is build config
Mobile TS/TSX import graph                 18/18 reachable
package dependency/devDependency overlap  none
```

These checks do **not** replace dependency-aware workspace typecheck/build.

## 6. PASS — i18n

`packages/i18n` owns shared locale metadata/resources but does not own platform lifecycle/storage.
Namespaces remain:

```text
common
admin
mobile
```

EN/VI key shapes are equal. Admin detects browser locale and persists explicit override in
`localStorage`; Mobile detects OS locale with `expo-localization` and persists override in
AsyncStorage. Display-timing labels and starter UI text used in current screens have EN/VI resources.

## 7. PASS — env ownership/setup smoke

No root `.env` is used. Each runtime/tool owns its own template.

An isolated copy of the repository ran:

```bash
./scripts/init-dev-env.sh
```

and verified:

- all seven expected local `.env` files are created;
- no server secret is copied into Admin/Mobile env;
- Core↔AI internal token is synchronized;
- Core↔Keycloak dev bootstrap token is synchronized;
- Docker↔Keycloak confidential bootstrap client secret is synchronized;
- Core runtime does **not** receive `LYREO_CORE_CLIENT_SECRET` before it has a runtime consumer;
- data importer receives the canonical Drive file URL;
- importer PostgreSQL URL tracks Docker dev DB credentials/port.

`SPRING_PROFILES_ACTIVE` remains a valid implicit Spring Boot env even though application source does
not read its literal name directly.

## 8. PASS — dataset bootstrap/safe extraction

Synthetic fixture test verified:

```text
valid archive install       PASS
optional SHA-256 check      PASS
make data-check equivalent  PASS
second fetch/idempotency    PASS
../ path traversal          REJECTED before write
```

Canonical template currently points to:

```text
DAUTOEIC_DATA_DIR=../../.data/datasets/toeic
DAUTOEIC_DATA_URL=https://drive.google.com/file/d/1FQgEswv3hUmT0Wv8Tyy9Jl_p9iLfoLZs/view?usp=sharing
DAUTOEIC_DATA_SHA256=
```

The real multi-GB Drive file was intentionally not downloaded in the sandbox. Google Drive share
links use ephemeral `uvx --from gdown gdown --fuzzy`; archive extraction is validated before moving
anything into the final dataset directory.

## 9. PASS — docs/static ownership audit

All repository Markdown files were scanned after the frontend/env changes:

```text
Markdown files checked                 24
broken relative Markdown links         0
exact long canonical paragraph copies  0
```

Current ownership remains intentionally separated:

- `README.md` -> onboarding/setup/run/repository map;
- `AGENTS.md` -> mandatory engineering contract;
- At the 2026-09-07 snapshot, `LYREO_PLATFORM_SPEC.md` was the detailed master; it is now a
  compatibility index and current owners are routed from `docs/README.md`.
- `ARCHITECTURE.md` -> system/module/runtime boundaries;
- `CONFIGURATION.md` -> env/runtime/admin/user preference precedence and ownership;
- `DATA_PIPELINES.md` -> external data/import semantics;
- `TECH_CHOICES.md` -> technology rationale/trade-offs;
- `DECISIONS.md` -> compact dated decisions;
- `DEVELOPMENT.md` -> developer workflow/troubleshooting;
- `OPERATIONS.md` -> deployment/runbook/incident concerns.

The master spec is deliberately detailed; that is not treated as harmful duplication. Shorter docs
should point to the owner instead of maintaining independent copies of the same rule.

## 10. PASS — Python tests

Command used in the final audit without creating pytest cache/bytecode:

```bash
PYTHONDONTWRITEBYTECODE=1 pytest -q -p no:cacheprovider \
  services/ai-service/tests tools/data-import/tests
```

Result:

```text
17 passed in 0.28s
```

These are current unit/helper/importer tests. No business logic was modified in this final frontend/
configuration audit.

## 11. BLOCKED / chưa thể chứng minh trong sandbox

### 11.1 Workspace lockfile + dependency-aware frontend build

A previous root-only lockfile was stale and intentionally removed. Current artifact does not contain
`pnpm-lock.yaml` because Corepack cannot reach npm registry from this sandbox, so a trustworthy
workspace lockfile cannot be generated here.

On the first networked machine with Node 24.20.0:

```bash
corepack enable
pnpm install
pnpm typecheck
pnpm build
```

Then review and commit the generated `pnpm-lock.yaml`. After that:

1. CI should switch from `pnpm install --no-frozen-lockfile` to `pnpm install --frozen-lockfile`;
2. Admin Docker build should copy `pnpm-lock.yaml` and use the frozen lockfile as well.

Until this is done, source/config static checks are green but frontend dependency resolution is not
fully reproducible.

### 11.2 Java/Maven

Sandbox Java is 21 while repo target is 25, and Maven Central DNS is unavailable. Run on proper host:

```bash
java -version   # expect 25
./mvnw -B test
```

No Java target/business code was changed just to accommodate the sandbox.

### 11.3 Docker/runtime topology

Docker is unavailable here, so actual container boot was not run. On dev/CI:

```bash
make init-env
make dev-config
make dev-infra
make keycloak-seed
```

Then smoke Admin/Mobile OIDC, Core/Flyway/PostgreSQL and background job recovery as the relevant team
slice is implemented.

### 11.4 Real TOEIC archive / live AI providers

The real multi-GB Drive dataset was not downloaded; only the bootstrap branch was tested with
synthetic archives. Live provider/GPU checks also require deployment credentials/hardware and remain
outside this foundation audit.

## 12. Recommended first networked verification order

```bash
mise install
corepack enable
make init-env
SKIP_DATA=1 make setup        # or `make setup` when the large dataset is wanted immediately
pnpm install                  # generate the real workspace lockfile
make validate
pnpm typecheck
pnpm build
./mvnw -B test
make dev-config
make dev-infra
make keycloak-seed
```

After that, commit the validated `pnpm-lock.yaml`, enable frozen installs in CI/Admin Docker build,
and remove this temporary handoff note when CI provides the same information continuously.
