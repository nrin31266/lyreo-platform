# @lyreo/design-system

Shared presentation contracts for Lyreo Admin Web and Mobile.

The package intentionally shares **tokens and visual semantics**, not platform components:

```text
brand + primitive palette + non-color foundation tokens
                         ↓
                semantic color roles
                         ↓
                     light / dark
                         ↓
          Admin Web adapter / Mobile adapter
```

## Ownership

- `src/primitives.ts` owns brand metadata and the primitive color palette.
- `src/foundation.json` is the single data source for non-color foundation tokens (`spacing`,
  `radius`, `motion`, `typography`). TypeScript re-exports them through the package API.
- `src/semantic.ts` owns `ThemeColors`, learner `semanticThemes` and operator `adminThemes`.
- Feature code should consume semantic roles such as `background`, `foreground`, `surface`,
  `primary`, `muted`, `border`, and `destructive`, never raw palette values.

Each light/dark map must have 100% parity with `ThemeColors`. Admin uses `adminThemes`; Mobile
uses learner `semanticThemes`. `ThemePreference` is `system | light | dark`.

The Mobile Tailwind adapter reads the public `@lyreo/design-system/foundation` export for radius
values instead of copying them. Admin publishes the same shared radius values into its Tailwind
CSS-variable adapter. This keeps platform configuration separate while preventing token drift.

Web and Mobile may implement their own components and styling engines; this package does not try to
share DOM and React Native component implementations.


## Admin workspace direction

Admin is a dense content console. Its palette uses slate background `#F4F6F9`, white surface
`#FFFFFF`, ink `#1B2433`, muted text `#536176`, indigo primary `#4558C8` and pale indigo selection
`#EDF0FF`. Dark mode uses slate surfaces with a lighter indigo action. Input boundaries meet 3:1;
primary, muted and functional text meet 4.5:1 on supported surfaces in the Admin regression test.
Learner themes remain independently defined.

The 49 px header and 216 px sidebar reserve space for work. Desktop navigation can collapse to
56 px icons with named links/tooltips; mobile uses the existing modal drawer. Page gutters are
16–20 px, panel insets 12–16 px, and ordinary controls 32–36 px. Transcript rows use dividers,
compact tools and content-sized textareas; the player stays beside the transcript/content tabs.
Controls retain visible focus, safe keyboard behavior and responsive wrapping.

Admin self-hosts IBM Plex Sans Variable through Fontsource (OFL-1.1), loading only Latin and
Vietnamese normal-weight subsets. Font assets ship with the app instead of requesting Google Fonts
at runtime. The Admin stylesheet owns this Web typography adapter; Mobile font loading is separate.
Sidebar composition references [shadcn sidebar-07](https://ui.shadcn.com/blocks/sidebar), customized
for the existing React Router/auth/i18n stack. Avoid promotional hero spacing, fake metrics and
sample analytics in operational screens.
