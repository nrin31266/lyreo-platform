# Yêu cầu Identity và Learner

Nguồn migration: đặc tả Lyreo trước khi tách owner; engineering security authority thuộc
[`AGENTS.md`](../../AGENTS.md#11-security-authority). Stories: [identity/learner](stories/identity-learner.md).

### FR-IDN-001 — Đăng nhập bắt buộc

Status: inherited. Phase đầu không có Guest Mode. Mobile/Admin dùng OIDC Authorization Code + PKCE;
Keycloak quản identity, credential và role `ADMIN`/`LEARNER`.

### FR-IDN-002 — JIT app-user provisioning

Status: inherited. Khi JWT hợp lệ lần đầu, Core map `sub` thành `app_user`; DB chỉ giữ product mapping
và snapshot cần thiết, không lưu password hoặc duplicate role không cần thiết.

### BR-IDN-001 — Authorization không dựa vào UI

Status: inherited. Core kiểm tra role và resource ownership; client route/menu chỉ là presentation.
Dev bootstrap endpoint phải bị tắt ngoài development.

### FR-IDN-003 — Onboarding lần đầu

Status: inherited. Learner lần đầu cung cấp current level, target, daily minutes, focus areas và
optional preferred content để nhận initial rule-based path suggestion.

### FR-IDN-004 — Persistent learner preferences

Status: inherited. Cross-device preferences gồm accent, translation/IPA/note display, thought group,
highlighting, Dictation hints và playback defaults. Session-only state như speed/loop/expanded note
không persist trừ khi learner chọn lưu mặc định.

### BR-IDN-002 — Storage theo độ nhạy

Status: inherited. Auth/session token dùng secret storage phù hợp; locale/theme và ordinary learning
preferences không dùng SecureStore. Precedence chi tiết: [Configuration](../CONFIGURATION.md).

### FR-IDN-005 — Learner Settings IA

Status: inherited. Settings được nhóm General, Audio & Playback, Shadowing, Dictation, Vocabulary &
Grammar, Pronunciation, Notifications và Accessibility; không gom thành một danh sách toggle phẳng.
