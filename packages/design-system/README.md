# @lyreo/design-system

Learner presentation foundation consumed by Mobile. Admin owns an independent shadcn foundation in
[Admin Web](../../apps/admin-web/README.md); it does not import these colors, radius or spacing.

- `src/primitives.ts` owns learner brand metadata and primitive palette.
- `src/foundation.json` owns learner spacing, radius, motion and typography.
- `src/semantic.ts` owns learner semantic roles and light/dark themes.
- Mobile consumes semantic roles and the public foundation export, not raw palette values.

Shared domain/API contracts and [translations](../i18n/README.md) do not imply shared visual tokens.
