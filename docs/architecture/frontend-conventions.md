# Frontend theme, localization and platform ownership

Mục đích: owner kỹ thuật cho shared presentation contracts và platform adapters. Package-specific
usage remains in `packages/design-system/README.md`, `packages/i18n/README.md` and app READMEs.

## Shared contracts

`packages/design-system` owns brand primitives, `foundation.json`, semantic `ThemeColors`, light/dark
maps and `system|light|dark` preference contract. Light/dark maps maintain complete role parity;
feature UI consumes semantic roles, not raw palette/color literals.

`packages/i18n` owns intentionally shared EN/VI resources split `common`, `admin`, `mobile`. It does
not initialize platform lifecycle/storage. Copy belongs in shared package only when semantically
shared; app-specific strings stay in its namespace.

## Platform adapters

Admin owns DOM components, Tailwind v4/Radix adapters, browser locale/localStorage and
`prefers-color-scheme`/`.dark` behavior. Mobile owns React Native components, NativeWind v4 with its
Tailwind 3.4 toolchain, `expo-localization`, AsyncStorage and `useColorScheme`/StatusBar/navigation
behavior. Web/Mobile do not share component implementations or Tailwind config.

Auth tokens may use secure storage. Locale/theme/ordinary preferences do not. `app.json` retains
automatic interface style so system appearance reaches Mobile.

## Experience constraints

Mobile is audio-first, low-friction and uses Lyrebird artwork only as a restrained presentation
asset. Do not ship placeholder mascot artwork as final. Loading/error/empty/network/accessibility
states are part of feature completeness. Admin Settings groups providers/routing/processing and
domain settings; Learner Settings groups learning/playback/practice/pronunciation/notification/
accessibility rather than a flat toggle wall.

### Lyrebird illustration contract

The mascot illustration should support these mood/context variants when artwork is finalized:

- `default` — idle/neutral;
- `listening` — audio playback or recording in progress;
- `speaking` — TTS or shadowing feedback;
- `thinking` — AI processing/loading;
- `celebrating` — level-up, mission completion, reward;
- `empty state` — no content/results.

Codebase prepares named contract and placeholder slots. Do not commit or ship fabricated artwork as
final asset in the starter. The mood list is a design contract, not a hard implementation
requirement for early phases.

### Mascot name in code

`Lyrebird` is a presentation/brand concern. Do not name business or domain classes after the mascot:

- `LyrebirdLessonService`, `LyrebirdRewardManager`, `LyrebirdAiProcessor` and similar patterns are
  prohibited — they would conflate brand identity with domain ownership.

Backend may use the name only in application metadata, OpenAPI title or service display name.
Frontend brand constants (product name, mascot name, tagline) are presentation data, not domain
invariants.

Implementation maturity and sample-data gaps are tracked in
[GAP-009](../requirements/gaps.md#gap-009--startermocked-implementation-maturity).
