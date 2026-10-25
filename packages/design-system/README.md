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

Admin uses neutral white/black/gray surfaces and text in `adminThemes`. Blue is reserved for
primary actions and focus: `#2563EB` in light mode, `#93C5FD` in dark mode.
`adminSidebarThemes` keeps navigation neutral with a pale blue selected state.
Functional text meets 4.5:1 and input/focus boundaries meet 3:1 in the Admin contrast regressions.
Learner themes remain independently defined.

The official `sidebar-07` standard sidebar composition has a 48px header and 216px navigation,
icon collapse, labelled tooltips and a mobile Sheet. A single vertical divider separates navigation
from the content; there is no inset frame. The avatar opens the account/sign-out menu.
Page gutters are 16px, panel insets 12px and ordinary controls 32–36px. Transcript rows use
dividers, compact tools and content-sized textareas.

Admin self-hosts Be Vietnam Pro through Fontsource (OFL-1.1), Latin and Vietnamese normal-weight
subsets at 400/500/600/700. The same family applies to navigation, controls and content; Tailwind's
`font-sans` maps to it too. Base-layer border tokens allow explicit border-color utilities to win
while avoiding `currentColor` borders in uncoloured shadcn primitives. Mobile font loading remains
independent. Avoid sample analytics or invented operational counts.
