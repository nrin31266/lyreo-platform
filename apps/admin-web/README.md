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

Authentication uses Authorization Code + PKCE with the public Keycloak client. Browser env values are baked into a production Vite build; the Dockerfile supplies relative API/auth defaults for the repository compose topology.

Admin owns its Web components and accessibility adapters. Use shared semantic light/dark tokens from [`design-system`](../../packages/design-system/README.md), shared translations from [`i18n`](../../packages/i18n/README.md), and local shadcn/Radix-style primitives. Keep feature pages organized by domain and avoid importing another page's private implementation. Use semantic classes rather than raw brand/theme color literals.


## Workspace UI

The compact console uses neutral/blue `adminThemes` and self-hosted IBM Plex Sans Variable
(Fontsource, OFL-1.1, Latin/Vietnamese subsets). The 48 px header and 216/48 px navigation
use the actual official `sidebar-07` block customized for React Router/auth/i18n. Mobile navigation
uses shadcn Sidebar's labelled Sheet, Escape dismissal, close control and focus restoration.
The login screen customizes official `login-03` with the existing OIDC entrypoint.

The full 54-file Radix/new-york component set was installed with `pnpm dlx shadcn@latest add --all`.
Existing components were previewed with `--dry-run`, `--diff` and `--view`, then merged preserving
the compact control styles, invalid states and translated `DialogContent.closeLabel` contract.
Blocks were installed with `pnpm dlx shadcn@latest add @shadcn/sidebar-07 @shadcn/login-03`;
unused team/project demonstration components and sample data were removed.

Use 16 px page gutters, 12 px panel insets and 32–36 px desktop controls; mobile touch targets
are at least 44 px. At 1200 px and above, official Resizable panels start at a 1/3 player and 2/3
editor split. Narrower screens stack the same mounted panels, so changing width does not discard
edits or recreate the player. Transcript and semantic review occupy separate Radix tabs.
State belongs to the parent so tab changes do not discard unsaved edits.
Tables scroll within labelled regions rather than expanding the page. The overview links to
implemented operations and does not invent counts or analytics.

`AppThemeProvider` remains the single theme owner. CSS aliases map its semantic tokens into
shadcn card/popover/accent/sidebar/chart roles. Sonner also reads this existing provider.
The installed Resizable adapter uses the installed react-resizable-panels v4 API (`Group`,
`Panel`, `Separator`) rather than the older API returned by the legacy new-york registry.
Unused components are available in source without a barrel import into the application.

Source inspection shows translated errors beside the URL and preserves the request correlation ID.
Source confirmation records operator responsibility and does not assert verified permission:
“Nguồn do người vận hành lựa chọn để phát triển và kiểm thử nội bộ. Người vận hành chịu trách nhiệm
xin phép và bảo đảm quyền sử dụng trước khi khai thác hoặc phát hành.”

Global CSS owns keyboard focus and reduced-motion behavior. Mobile continues to use learner
`semanticThemes`; Admin palette/font changes do not change that runtime adapter.

Browser fixture sessions are not evidence for live OIDC, YouTube playback or AI quality.
Consult the [plan completion audit](../../docs/features/lesson-build.md#plan-completion-audit--2026-10-25)
for verified live OIDC/player/publication checks, source retirement and remaining human quality/billing gates.
