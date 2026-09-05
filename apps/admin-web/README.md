# Lyreo Admin Web

React + Vite internal/admin application.

Admin Web chịu trách nhiệm những flow management/configuration như:

- Lesson Builder;
- theo dõi/cancel background job;
- AI provider/model routing;
- curriculum/content management (phát triển tiếp);
- Lexicon/dataset maintenance (phát triển tiếp).

Không đưa secret provider vào browser. Browser chỉ nhận public OIDC/API URLs.

## Development

```bash
cp .env.example .env
pnpm --filter @lyreo/admin-web dev
```

Default: `http://localhost:5173`.

## Authentication

OIDC Authorization Code + PKCE với public Keycloak client `lyreo-admin-web`.

## Production container

Vite env được bake tại build time. Dockerfile có default relative URLs `/api` và `/auth` cho self-host compose; deployment khác có thể truyền build args `VITE_*`.
