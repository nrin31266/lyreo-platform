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
One authenticated SSE stream per Admin layout invalidates job/draft queries, resynchronizes on
reconnect and falls back to polling; see the [jobs protocol](../../docs/architecture/background-jobs.md).
React Hook Form and Zod validate forms. Core remains the final validation and lifecycle authority.
Editor text, selection and playback state stay local and cannot be overwritten by background refresh.
Transcript lists above 20 visible sentences use TanStack Virtual with measured row heights and overscan.
Draft values remain in the editor state, and the focused row stays mounted while scrolling. Split sentences
receive local editor keys that are omitted from API payloads.
Sentence fields show their input surface; the transcript card fills its bounded parent so the list scrolls
instead of growing behind the panel. Merge help names the next sentence in the original order, including
when suggestions hide intermediate rows. Possible repeated suffixes are review hints, never automatic deletions.

The sidebar uses one navigation configuration, nested menus and explicit Coming soon entries for
unimplemented areas. These entries do not imply feature completeness. AI configuration uses one sidebar entry and top tabs for providers and routing. Task pages prefer
a viewport-height workspace with independent scrolling for long lists and forms; desktop authoring uses resizable player/editor panes, while smaller screens switch
between Player and Editor without expanding the whole page. Submit a source URL with Search or Enter
to inspect it; pasting alone does not load it. The same Search button refreshes/retries, preserving
edits and valid ranges on a same-URL refresh. No rights-attestation checkbox is required. A single footer
groups the current step’s submissions. Loading states use skeletons.

## Content workflow

`/lessons` lists/filters/pages lessons; `/lessons/new` inspects sources and creates drafts;
`/lessons/:lessonId` resumes drafts or opens the separate published view. Categories and source
channels share `/lessons/organization` with URL-addressable tabs and independent queries. Old
category/channel routes redirect to the corresponding tab. Filters remain in the URL.

Metadata (display title, description, image URL and categories) can change independently of published
learning content. Draft deletion is soft and requires stopped jobs. Published lessons can be unlisted
and relisted; learning history is preserved. Source thumbnails and channel avatars have fallbacks.
Explicit channel metadata sync supports up to 50 selected IDs through Core jobs; configure the
server-only `YOUTUBE_DATA_API_KEY` to enable it. No automatic video import is performed.

Source selection, timecodes, independent playback seek, transcript editing, alignment, karaoke review,
semantic review, candidates and publication follow [Lesson Build](../../docs/features/lesson-build.md).
Source inspection does not acquire audio; range confirmation is explicit. YouTube owns the playback
clock. `LESSON_TIMING_REUSE_DRAFT` stays a Core quality policy, default false pending human evidence.

Tests cover observable behavior and stay with their owner; shared setup stays in `src/test`.
Prioritize desktop/laptop layouts and browser checks at 1280px and 1440px. Retain existing responsive
behavior without a separate phone/tablet optimization pass. Check light/dark, keyboard navigation, dirty navigation,
source-region interactions and the complete authoring workflow. Unit tests do not prove visual layout
or pedagogical quality. Credentials are never persisted in public Vite configuration.


Authoring pages use resizable/collapsible panes with natural scrolling for long tasks. Playback clock
subscriptions belong to leaf controls, not editing forms. Source selection is metadata-only with a
timeline seek control and React Timeline Editor range handles. Zoom in/out, full-video fit,
selection fit (10% padding on either side), pointer-centred mouse-wheel zoom and horizontal scrolling
only change the viewport. Vertical wheel zooms on the timeline; horizontal/Shift-wheel and browser
Ctrl/Meta-wheel keep their native behavior;
millisecond timecodes and keyboard controls remain available. The timeline has no vertical scroll;
horizontal scrolling remains available when zoomed in. The most zoomed-out view fits the exact source duration without horizontal scrolling. The compact track
uses gray outside the selection, a colored selected segment, and an explicit source-end marker.
YouTube videos up to ten minutes are selected in full by default; longer videos require an explicit range.
`VITE_YOUTUBE_AUTO_SELECT_MAX_MINUTES` can lower this threshold
or disable it with `0` (default/cap: `10`; invalid values fall back to `10`). Vite reads it at startup/build time.
URL offsets position playback independently. An obsolete range after a duration refresh resets to this default.
The full-video selection button is hidden above ten minutes. Marks and timecodes remain available for manual adjustment.
Desktop source selection groups the 16:9 video, playback and range selection in the left column,
with lesson metadata in the right column and one submission row. Playback, speed, looping, zoom,
range marks, full-video selection and help share one compact toolbar above the timeline.
Timecodes remain in their own labeled fields; help uses separate bullet points.
Short desktop screens scroll the page while keeping submission available;
below 960px the page stacks naturally.
Title/categories remain visible; used ranges and optional metadata are collapsed. Icon actions have
hover/focus tooltips and longer selection help opens in a dialog. Waveform UI is deferred.


Authoring stays in one workspace from source preparation through Edit words / Listen & check.
There is no numbered step navigator or unfinished content/publish screen. Text retains its saved
voice and hides preliminary sentence rows until generated audio is ready; the primary source action
saves then starts TTS, while Save draft remains free of inference. Save and align timing stops at
any failed checkpoint; final confirmation saves a reviewed, unpublished draft.
Sentence icons preserve split caret position and show observed/approximate timing explicitly.
A compact colored status above the workspace shows preparation, active work, wait, cancellation
or failure, with operation-specific recovery labels and a jump to an affected sentence when a timing diagnostic supplies it.
Technical history stays in the footer with playback/seek/loop/speed and the primary action. When all word timing is missing,
Listen & check explains the next action instead of repeating a warning for every sentence.
Karaoke marks only the currently spoken observed word/group with a fitted underline. Previous words
lose their underline; silence shows none. Text backgrounds remain unfilled.
Dedicated realignment of valid timing has its own icon.
Job history exposes retry/cancel/inspect icons and expands model usage/timestamps on demand.
Elapsed time uses persisted job timestamps; reopening a page or sheet does not reset it.
Both word and timing lists reuse the existing virtualizer above 20 rows. A focused word editor stays
mounted while scrolling; timing selection scrolls into the measured list and expands only one set of
word controls. Review starts paused; sentence/word actions play immediately and stop at the selected
sentence end. Footer icons toggle continuous playback or sentence looping without starting playback.
Continuous playback follows karaoke selection through every sentence in the draft. Changing review tabs/revision or
opening timing edits pauses the video. Timing review has a compact warning toolbar and confirmation
below the list, with grouped-word guidance available from a focusable information label.

Text and Audio source tabs reuse the authoring review workspace. Text saves a draft before explicit
Kokoro synthesis; Audio accepts private file uploads and range selection. See the canonical
[Lesson source requirements](../../docs/requirements/lesson.md#fr-lsn-011--text-and-uploaded-audio-authoring).

Sentence visibility uses a single-click Visible/Hidden button with an eye icon and semantic state colors in Edit words. It preserves text/audio
and is independent of exercise usage. Listen & check shows only visible
sentences; continuous playback keeps the original audio and pauses, while its highlights follow the
media clock without sentence-boundary seeks; Admin can still preview/edit hidden rows in Edit words and restore them.
