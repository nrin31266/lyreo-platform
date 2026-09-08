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
- `src/semantic.ts` owns `ThemeColors`, `semanticThemes.light`, and `semanticThemes.dark`.
- Feature code should consume semantic roles such as `background`, `foreground`, `surface`,
  `primary`, `muted`, `border`, and `destructive`, never raw palette values.

`semanticThemes.light` and `semanticThemes.dark` are the authoritative runtime color maps and must
have 100% parity with `ThemeColors`. `ThemePreference` is `system | light | dark`.

The Mobile Tailwind adapter reads the public `@lyreo/design-system/foundation` export for radius
values instead of copying them. Admin publishes the same shared radius values into its Tailwind
CSS-variable adapter. This keeps platform configuration separate while preventing token drift.

Web and Mobile may implement their own components and styling engines; this package does not try to
share DOM and React Native component implementations.
