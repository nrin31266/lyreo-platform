# Yêu cầu Identity và Learner

See engineering security authority thuộc
[`AGENTS.md`](../../AGENTS.md). Chi tiết cấu hình realm/client: [`infra/keycloak/README.md`](../../infra/keycloak/README.md).

### FR-IDN-001 — Đăng nhập bắt buộc

Phase đầu không có Guest Mode. Mobile/Admin dùng OIDC Authorization Code + PKCE;
Keycloak quản identity, credential và role `ADMIN`/`LEARNER`.

### FR-IDN-002 — JIT app-user provisioning

Khi JWT hợp lệ lần đầu, Core map `sub` thành `app_user`; DB chỉ giữ product mapping
và snapshot cần thiết, không lưu password hoặc duplicate role không cần thiết.

### BR-IDN-001 — Authorization không dựa vào UI

Core kiểm tra role và resource ownership; client route/menu chỉ là presentation.
Dev bootstrap endpoint phải bị tắt ngoài development.

### FR-IDN-003 — Onboarding lần đầu

Sau đăng nhập, learner chưa hoàn tất thiết lập đi qua đúng ba bước:

1. **Mục tiêu học** — Giao tiếp, Công việc, TOEIC hoặc Chưa xác định.
2. **Trình độ hiện tại** — mô tả ngắn điều người học tự đánh giá mình làm được,
   từ A1 đến C2, hoặc Chưa chắc.
3. **Thời lượng mỗi ngày** — chọn 5, 10, 15 hoặc 20 phút, với 10 phút được
   gợi ý để xác nhận hoặc đổi. Đây là bước riêng.

Quay lại giữ lựa chọn trong phiên thiết lập. **Lưu và bắt đầu** gửi cả ba câu trả lời
và vào Home sau khi Core xác nhận đã lưu. Lỗi tải có hành động Thử lại. Lỗi lưu giữ
câu trả lời để gửi lại. Khi Core xác nhận đã hoàn tất, lần đăng nhập sau đi thẳng Home.
Core trả gợi ý chủ đề, độ khó và thời lượng để Mobile dùng khi kết nối luồng học.
Quyết định sản phẩm: [D-036](../DECISIONS.md#d-036--learner-profile-and-three-step-onboarding-2026-11-23).

### FR-IDN-004 — Persistent learner preferences

Cross-device preferences gồm accent, translation/IPA/note display, thought group,
highlighting, Dictation hints và playback defaults. Session-only state như speed/loop/expanded note
không persist trừ khi learner chọn lưu mặc định.

### BR-IDN-002 — Storage theo độ nhạy

Auth/session token dùng secret storage phù hợp; locale/theme và ordinary learning
preferences không dùng SecureStore. Precedence chi tiết: [Configuration](../CONFIGURATION.md).

### FR-IDN-005 — Learner Settings IA

Settings được nhóm General, Audio & Playback, Shadowing, Dictation, Vocabulary &
Grammar, Pronunciation, Notifications và Accessibility; không gom thành một danh sách toggle phẳng.

### FR-IDN-006 — Learner profile and Admin directory

Learner chỉnh tên hiển thị, mục tiêu, trình độ tự đánh giá, thời lượng mỗi ngày và
kỹ năng ưu tiên trong Hồ sơ học tập. Tên hiển thị và kỹ năng ưu tiên là tùy chọn.
Admin tra cứu learner đã truy cập API Learner, kể cả khi đang chờ hoàn tất onboarding.
Danh sách hỗ trợ tìm tên hoặc email và lọc trạng thái. Admin có thể sửa giúp tên
hiển thị và ảnh đại diện từ hồ sơ chi tiết. Learner tiếp tục tự quản lý các lựa chọn học.
Admin bấm bút cạnh tên để sửa tại chỗ hoặc bấm ảnh để mở hộp thoại đổi avatar.
Ảnh tải lên được căn và xem trước trong khung tròn trước khi lưu.
Ảnh đại diện mặc định dùng chữ viết tắt theo tên hoặc email. Learner và Admin có thể
chọn ảnh từ các nhóm Thú, Hoa, Nhân vật và Truyện tranh trong cùng một danh sách cuộn,
hoặc tải JPEG/PNG. Core giữ ảnh riêng tư, chỉ phục vụ cho
chủ hồ sơ và Admin.
Trong hồ sơ Admin, mỗi học viên có một gói Free hoặc Plus và lịch sử theo từng lần
chuyển gói. Admin chỉ có quyền xem mục tiêu điểm TOEIC L&R trong hồ sơ.

Admin có thể đặt lại mật khẩu cho tài khoản học viên với tùy chọn yêu cầu đổi
mật khẩu ở lần đăng nhập tiếp theo. Core chuyển dữ liệu mật khẩu sang Keycloak
trong yêu cầu đang xử lý. Nhật ký chỉ ghi người thực hiện, tài khoản, thao tác và thời điểm.

Admin có thể nhập lý do khi khóa hoặc mở khóa. Lý do được lưu trong nhật ký nếu có.
Core xác nhận tài khoản thuộc phạm vi quản lý với Keycloak trong mỗi yêu cầu khóa hoặc mở khóa.
Nếu không thể xác nhận, Core giữ nguyên trạng thái. Sau khi xác nhận, Core chặn các phiên đang dùng
trước khi đồng bộ khóa và đăng xuất với Keycloak. Nếu đồng bộ thất bại, tài khoản vẫn bị chặn
và Admin có thể thử lại. Sau khi mở khóa, học viên đăng nhập lại để lấy phiên mới.
Tài khoản có quyền Admin, service account và hồ sơ mẫu không có người dùng Keycloak
nằm ngoài phạm vi thao tác.
[Hợp đồng HTTP](../architecture/http-api-contract.md#learner-profile-onboarding-and-admin-directory).

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

Given learner hợp lệ, when gửi mục tiêu, trình độ và thời lượng hợp lệ, then Core lưu
hồ sơ và mốc hoàn tất, trả gợi ý khởi đầu theo quy tắc. Gửi lại trả hồ sơ đã lưu
và giữ chỉnh sửa cùng preferences.

#### AC-IDN-005 — Learner directory and profile edit

Given Core ghi nhận lượt truy cập Learner, when Admin tìm hoặc lọc danh sách,
then hồ sơ có trạng thái phù hợp với mốc hoàn tất. Chỉ learner sở hữu hồ sơ mới được
sửa dữ liệu học tập của mình sau onboarding. Lưu preferences trước onboarding vẫn giữ
trạng thái chưa hoàn tất.

#### AC-IDN-004 — Preference precedence

Given persistent preference và session override, when render practice, then session override áp dụng
cho phiên hiện tại; chỉ lựa chọn lưu mặc định mới cập nhật persistent preference.
