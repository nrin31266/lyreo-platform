# Lyreo Mobile

Expo SDK 57 + React Native 0.86, Development Build/Prebuild, New Architecture.

Mobile is the **learner experience**, not an admin console. The initial product requires login before
entering learning state so progress/SRS/Curriculum/Diamond always have an owner.

## Main learner areas

```text
Home / Continue learning
Lesson (Dictation / Shadowing / contextual notes)
Vocabulary SRS
TOEIC
Progress
Settings
```

Chat keeps a module boundary but remains low priority.

## UI foundation

Mobile uses:

- NativeWind v4 with its Tailwind v3.4 toolchain;
- shared semantic light/dark tokens from `@lyreo/design-system`;
- selected repo-owned React Native Reusables-style primitives under `src/components/ui`;
- shared EN/VI resources from `@lyreo/i18n`;
- `expo-localization` for initial OS locale detection;
- AsyncStorage for ordinary locale/theme preferences;
- `system | light | dark` theme preference, default `system`.

Expo Router runtime dependencies are declared **directly** in this app (`expo-router`,
`expo-constants`, `expo-linking`, `expo-status-bar`, `react-native-safe-area-context`, and
`react-native-screens`) instead of relying on pnpm hoisting/transitive dependencies. Keep their
versions aligned with Expo SDK 57 via `expo install` when upgrading the SDK.

Dark-mode flow is deliberately platform-native: `expo.userInterfaceStyle=automatic` lets React
Native observe device appearance; `AppThemeProvider` resolves `system | light | dark` with
`useColorScheme()`, maps the resolved mode through `semanticThemes`, and publishes those roles as
NativeWind CSS variables on the root view. Screens consume semantic classes such as
`bg-background`, `text-foreground`, and `border-border` rather than branching on dark mode.

Web and Mobile intentionally do **not** share component implementations or Tailwind config. They
share semantic contracts/resources only. Starter screens should use semantic NativeWind classes or
`useAppTheme()` instead of raw theme/brand color literals.

## Why Development Build

Lyreo needs audio playback/recording and may add native capabilities later. Do not design production
runtime around Expo Go-only constraints.

## Environment

```bash
cp .env.example .env
```

`EXPO_PUBLIC_*` is public metadata. Do not place API keys/client secrets there.

Android Emulator defaults to `10.0.2.2` for host-machine access. A physical device needs a reachable
LAN IP or an appropriate development tunnel.

## Authentication and local storage

Authorization Code + PKCE uses public Keycloak client `lyreo-mobile`.

`expo-secure-store` is reserved for authentication/session credentials (access/refresh/id token and
related auth-session metadata). Ordinary theme/locale preferences use AsyncStorage instead of a
secret store.

## Run

```bash
pnpm --filter @lyreo/mobile start
pnpm --filter @lyreo/mobile android
```

After adding/changing native modules, Expo plugins, NativeWind native integration, or SDK versions,
rebuild the Development Build. When NativeWind configuration changes, restart Metro with a clean
cache if styles appear stale.

Typecheck after dependencies are installed:

```bash
pnpm --filter @lyreo/mobile typecheck
```

## Product principle

Learning screens prioritize focus and modern feedback rather than showing every annotation at once.
Learner preference controls `OFF / TAP_TO_SHOW / AFTER_ATTEMPT / ALWAYS` for IPA/translation/notes
when admin policy permits it.
