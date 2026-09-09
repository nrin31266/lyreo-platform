# Stories — Identity và Learner

Requirements: [Identity/Learner](../identity-learner.md).

### US-IDN-001 — Đăng nhập an toàn

Là learner/admin, tôi muốn đăng nhập qua identity provider để dùng đúng dữ liệu và quyền của mình.

#### AC-IDN-001 — Session hợp lệ

Given OIDC session hợp lệ, when app gọi Core, then JWT được xác thực, app-user được provision nếu
cần, và resource/role được kiểm tra phía server.

#### AC-IDN-002 — Session không hợp lệ

Given không có session hợp lệ, when mở app protected, then người dùng được đưa tới login/register;
không có guest progress tạm được tạo.

### US-IDN-002 — Onboarding và preferences

Là learner lần đầu, tôi muốn khai báo mục tiêu và cách hiển thị để nhận đường học ban đầu và trải
nghiệm phù hợp trên các thiết bị.

#### AC-IDN-003 — Onboarding result

Given learner hợp lệ, when gửi level/target/daily minutes/focus hợp lệ, then profile lưu persistent
state và trả initial rule-based suggestion.

#### AC-IDN-004 — Preference precedence

Given persistent preference và session override, when render practice, then session override áp dụng
cho phiên hiện tại; chỉ lựa chọn lưu mặc định mới cập nhật persistent preference.
