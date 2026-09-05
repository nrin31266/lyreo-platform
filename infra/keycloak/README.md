# Keycloak bootstrap cho Lyreo

Lyreo dùng Keycloak làm **Identity Provider/OIDC server**. Core Service không tự quản password
và không duplicate role nếu không cần thiết. Local Keycloak persist vào PostgreSQL để restart
container không làm mất user/config đã bootstrap.

## 1. Realm/client chuẩn

| Thành phần | Giá trị | Loại |
|---|---|---|
| Realm | `lyreo` | — |
| Learner role | `LEARNER` | realm role |
| Admin role | `ADMIN` | realm role |
| Admin Web | `lyreo-admin-web` | public client + Authorization Code + PKCE |
| Mobile | `lyreo-mobile` | public client + Authorization Code + PKCE |
| Core Service | `lyreo-core-service` | confidential/service-account client |

`infra/keycloak/import/lyreo-realm.json` chứa topology để developer không click cấu hình thủ công.
Confidential Core secret thật được áp lại bởi `bootstrap-keycloak.sh`; không tin placeholder realm JSON như production secret.

## 2. Local DB topology

`compose.dev.yml` dùng cùng một PostgreSQL server nhưng hai database độc lập:

```text
lyreo_dev       business data
lyreo_keycloak  Keycloak persistence
```

`infra/postgres/init/01-create-keycloak-db.sh` chỉ chạy khi PostgreSQL volume được initialize lần đầu.
Nếu đã có volume cũ trước khi script tồn tại, recreate local volume hoặc tạo DB thủ công.

## 3. Local setup

```bash
./scripts/init-dev-env.sh
docker compose --env-file infra/docker/.env -f compose.dev.yml up -d
make keycloak-seed
```

`init-dev-env.sh` đồng bộ:

- Core confidential client secret;
- Keycloak bootstrap env;
- Core dev bootstrap token.

`make keycloak-seed`:

1. đợi Keycloak ready;
2. login bằng bootstrap admin;
3. verify/create `ADMIN`, `LEARNER`;
4. apply current `LYREO_CORE_CLIENT_SECRET`;
5. seed dev admin/learner idempotently;
6. nếu Core chạy profile `dev`, mirror subject/email vào `app_user` qua guarded dev endpoint.

## 4. Dev identities

Dev user/password nằm trong `infra/keycloak/.env`, copy từ `.env.example` và gitignored.
Không dùng password mẫu ngoài local development.

## 5. Login model

Admin/Mobile dùng **Authorization Code + PKCE** với public client. Không gửi username/password tới Core Service.

Core validates JWT bằng:

```text
KEYCLOAK_ISSUER_URI
KEYCLOAK_JWK_SET_URI
```

Tách hai URL giúp production reverse-proxy deployment giữ issuer public nhưng fetch JWK qua internal Docker network.

## 6. Production/self-host path

`compose.prod.yml` starter expose Keycloak dưới `/auth` qua edge Nginx:

```text
http(s)://<public-host>/auth
```

Keycloak container dùng:

```text
KC_HTTP_RELATIVE_PATH=/auth
KC_PROXY_HEADERS=xforwarded
```

Core expected issuer lấy từ:

```text
KEYCLOAK_PUBLIC_URL=<public-origin>/auth
```

và JWK fetch nội bộ:

```text
http://keycloak:8080/auth/realms/lyreo/protocol/openid-connect/certs
```

Production thật phải thay `http://localhost/auth` bằng public HTTPS URL/domain thật.

## 7. User provisioning vào Lyreo DB

Keycloak là source of truth identity/role.

Lyreo DB chỉ giữ:

```text
app_user.id
app_user.keycloak_subject
app_user.email_snapshot
```

Production user được JIT-provision khi JWT hợp lệ lần đầu. Không copy password sang PostgreSQL.

## 8. Security rules

- Không expose `lyreo-core-service` secret sang Admin/Mobile.
- Không commit Keycloak admin password/service-account secret.
- Dev bootstrap endpoint chỉ tồn tại/hoạt động profile `dev`.
- Redirect URI production phải explicit và HTTPS.
- Realm role checks vẫn được enforce ở Core Service, không chỉ ẩn menu frontend.
- Backup Keycloak DB trước upgrade production.
