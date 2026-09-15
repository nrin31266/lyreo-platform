# Lyreo Admin Web

React + Vite internal/admin application.

Admin Web owns management/configuration flows such as:

- Lesson Builder;
- background job monitoring/cancellation;
- AI provider/model routing;
- curriculum/content management (later slice);
- Lexicon/dataset maintenance (later slice).

Do not expose provider secrets to the browser. Browser env contains public OIDC/API metadata only.

## UI foundation

Admin uses:

- Tailwind CSS v4 through `@tailwindcss/vite`;
- repo-owned shadcn/Radix-style primitives under `src/components/ui`;
- shared semantic light/dark tokens from `@lyreo/design-system`;
- shared EN/VI resources from `@lyreo/i18n`;
- browser locale + `localStorage` for explicit locale preference;
- `system | light | dark` theme preference in `localStorage`.

Dark mode stays class-based on the DOM: `AppThemeProvider` follows
`prefers-color-scheme` when preference is `system`, toggles `.dark` on `document.documentElement`,
and publishes the resolved `semanticThemes` roles as CSS variables. Tailwind utilities consume
those semantic variables; feature screens should not contain their own light/dark color branches.

`packages/design-system` shares semantic roles, **not DOM component implementations**. Admin owns
its browser accessibility/layout adapters and may use Radix primitives where they materially help.
Feature UI should use semantic classes (`bg-background`, `text-foreground`, `border-border`, etc.)
instead of raw theme/brand color literals.

## Development

```bash
cp .env.example .env
pnpm --filter @lyreo/admin-web dev
```

Default: `http://localhost:5173`.

Typecheck/build after dependencies are installed:

```bash
pnpm --filter @lyreo/admin-web typecheck
pnpm --filter @lyreo/admin-web build
```

## Authentication

OIDC Authorization Code + PKCE with public Keycloak client `lyreo-admin-web`.

## Production container

Vite env is baked at build time. Dockerfile defaults to relative `/api` and `/auth` URLs for the
self-host compose topology; other deployments may pass public `VITE_*` build args.

## Architecture and Structure

```text
src/
├── app/                  # Application composition: router, shell layout, auth, providers
│   ├── auth/             # Auth module: AuthProvider, ProtectedRoute, AuthCallbackPage, roles
│   ├── layout/           # Shell layout: AdminLayout, AdminSidebar, AdminHeader, admin-navigation
│   └── providers/        # Root providers: AppProviders, AppThemeProvider
├── pages/                # Domain vertical slices (self-contained per route)
│   ├── overview/         # OverviewPage
│   ├── jobs/             # JobsPage, jobs.api, jobs.types
│   ├── ai-settings/      # AiSettingsPage, ai-settings.api, ai-settings.types
│   ├── lesson-builder/   # LessonBuilderPage, lesson-builder.api, lesson-builder.types
│   ├── curriculum/       # CurriculumPage placeholder
│   └── lexicon/          # LexiconPage placeholder
├── components/ui/        # Repo-owned shadcn / Radix primitives (button, card, dialog, etc.)
├── config/               # Static config & typed environment (config/env.ts)
├── lib/                  # Shared infrastructure: lib/api (fetcher, Problem Details), lib/oidc, lib/utils
├── i18n/                 # i18next instance and locale persistence
└── styles/               # Global stylesheet (globals.css) and Tailwind v4 setup
```

### Layering and import rules

- **`config` / `lib` / `i18n` / `components/ui`** -> **`pages`** -> **`app`**.
- Cross-directory imports use the path alias `@/*`.
- Same-page vertical slices use relative imports (`./*`).
- **No cross-page imports**: pages never import internal implementation from another page slice.
- `components/ui/*` and `lib/utils.ts` belong to the local shadcn CLI contract defined in `components.json`.

### How to add a new Admin page

1. Create a vertical slice folder under `src/pages/<domain>/`:
   - `<Domain>Page.tsx` for the UI and component state;
   - `<domain>.api.ts` for route-specific HTTP requests using `api<T>()`;
   - `<domain>.types.ts` for page DTOs.
2. Register the route path and label key in `src/app/layout/admin-navigation.ts`.
3. Add the nested `<Route>` under `AdminLayout` in `src/app/router.tsx`.

