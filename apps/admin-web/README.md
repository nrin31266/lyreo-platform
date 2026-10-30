# Lyreo Admin Web

React/Vite client for content managers and operators. Product scope lives in the [PRD](../../docs/product/prd.md); this app owns browser UI and public OIDC/API configuration. Never put provider or server secrets in `VITE_*` values.

## Local development

From this directory, copy `.env.example` to `.env` and set the public endpoints. From the repository root, run `make admin` after `make dev-infra` and `make keycloak-seed`; the dev server normally listens on `http://localhost:5173`.

```bash
pnpm --filter @lyreo/admin-web typecheck
pnpm --filter @lyreo/admin-web test
pnpm --filter @lyreo/admin-web build
```

Tests use Vitest, React Testing Library, and jsdom. Tests cover editor polling/checkpoint behavior, source errors and navigation focus/collapse persistence.

## Local conventions

Admin uses React/Vite, React Router, local shadcn Radix components and the full `b1aIuQ2XC` preset
(Luma, Stone/Blue, Raleway, Remix Icon, large radius and translucent menus), with pointer cursors.
`src/styles/globals.css` owns the default preset palette and light/dark success/warning colors;
feature styles live with their owners. Admin has no dependency on Mobile theme tokens. Shared EN/VI strings live in `packages/i18n`.

Axios owns authenticated transport, timeout, cancellation and Problem Details. TanStack Query owns
server data, invalidation and polling while jobs are active; mutations do not retry automatically.
React Hook Form and Zod validate forms. Core remains the final validation and lifecycle authority.
Editor text, selection and playback state stay local and cannot be overwritten by background refresh.

The sidebar uses one navigation configuration, nested menus and explicit Coming soon entries for
unimplemented areas. These entries do not imply feature completeness. AI configuration uses one sidebar entry and top tabs for providers and routing. Task pages prefer
a viewport-height workspace with independent scrolling for long lists and forms; desktop authoring uses resizable player/editor panes, while smaller screens switch
between Player and Editor without expanding the whole page. Source URL paste opens the source
workspace after metadata inspection; no rights-attestation checkbox is required. A single footer
groups the current step’s submissions. Loading states use skeletons.

## Content workflow

`/lessons` lists/filters/pages lessons; `/lessons/new` inspects sources and creates drafts;
`/lessons/:lessonId` resumes drafts or opens the separate published view. Categories and source
channels have dedicated screens and queries. Filters remain in the URL.

Metadata (display title, description, image URL and categories) can change independently of published
learning content. Draft deletion is soft and requires stopped jobs. Published lessons can be unlisted
and relisted; learning history is preserved. Source thumbnails and channel avatars have fallbacks.
Explicit channel metadata sync supports up to 50 selected IDs through Core jobs; configure the
server-only `YOUTUBE_DATA_API_KEY` to enable it. No automatic video import is performed.

Source selection, timecodes, waveform preparation, transcript editing, alignment, karaoke review,
semantic review, candidates and publication follow [Lesson Build](../../docs/features/lesson-build.md).
Source inspection does not acquire audio; range confirmation is explicit. YouTube owns the playback
clock. `LESSON_TIMING_REUSE_DRAFT` stays a Core quality policy, default false pending human evidence.

Tests cover observable behavior and stay with their owner; shared setup stays in `src/test`.
Prioritize desktop/laptop layouts and browser checks at 1280px and 1440px. Retain existing responsive
behavior without a separate phone/tablet optimization pass. Check light/dark, keyboard navigation, dirty navigation,
source-region interactions and the complete authoring workflow. Unit tests do not prove visual layout
or pedagogical quality. Credentials are never persisted in public Vite configuration.
