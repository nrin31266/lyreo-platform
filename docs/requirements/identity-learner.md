# Yêu cầu Identity và Learner

Nguồn migration: đặc tả Lyreo trước khi tách owner; engineering security authority thuộc
[`AGENTS.md`](../../AGENTS.md#11-security-authority). Chi tiết cấu hình realm/client: [`infra/keycloak/README.md`](../../infra/keycloak/README.md).

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

## User Stories & Acceptance Criteria

<a id="us-idn-001--đăng-nhập-an-toàn"></a>
### US-IDN-001 — Đăng nhập an toàn

Là learner/admin, tôi muốn đăng nhập qua identity provider để dùng đúng dữ liệu và quyền của mình.

#### AC-IDN-001 — Session hợp lệ

Given OIDC session hợp lệ, when app gọi Core, then JWT được xác thực, app-user được provision nếu
cần, và resource/role được kiểm tra phía server.

#### AC-IDN-002 — Session không hợp lệ

Given không có session hợp lệ, when mở app protected, then người dùng được đưa tới login/register;
không có guest progress tạm được tạo.

<a id="us-idn-002--onboarding-và-preferences"></a>
### US-IDN-002 — Onboarding và preferences

Là learner lần đầu, tôi muốn khai báo mục tiêu và cách hiển thị để nhận đường học ban đầu và trải
nghiệm phù hợp trên các thiết bị.

#### AC-IDN-003 — Onboarding result

Given learner hợp lệ, when gửi level/target/daily minutes/focus hợp lệ, then profile lưu persistent
state và trả initial rule-based suggestion.

#### AC-IDN-004 — Preference precedence

Given persistent preference và session override, when render practice, then session override áp dụng
cho phiên hiện tại; chỉ lựa chọn lưu mặc định mới cập nhật persistent preference.
