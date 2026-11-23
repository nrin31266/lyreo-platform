# Tài khoản development local

Tra cứu nhanh các tài khoản mẫu sau khi chạy `make setup`.
Các mật khẩu dưới đây là mặc định công khai dành riêng cho local development.

| Dùng cho | Địa chỉ | Tài khoản | Mật khẩu mặc định | Quyền |
|---|---|---|---|---|
| Lyreo Admin Web | [localhost:5173](http://localhost:5173) | `admin` | `Password123!` | `ADMIN`, `LEARNER` trong realm `lyreo` |
| Lyreo learner / Mobile | Realm `lyreo` tại [localhost:8081](http://localhost:8081/realms/lyreo) | `learner` | `Password123!` | `LEARNER` |
| Keycloak Admin Console | [localhost:8081/admin](http://localhost:8081/admin/) | `admin` | `admin` | Quản trị Keycloak trong realm `master` |

Mở Admin Web, bấm **Đăng nhập không gian làm việc**, rồi dùng `admin` của realm `lyreo`.
Keycloak Admin Console dùng tài khoản bootstrap trong realm `master`.

Giá trị mặc định được định nghĩa trong
[`infra/keycloak/.env.example`](../infra/keycloak/.env.example). Nếu máy đã đổi tài khoản hoặc
mật khẩu, xem file local `infra/keycloak/.env`; cấu hình bootstrap admin của container thuộc
`infra/docker/.env`. Không đưa mật khẩu riêng, token bootstrap hay client secret vào tài liệu này.

`make setup` tạo hoặc cập nhật tài khoản `admin` và `learner` theo `infra/keycloak/.env`,
đồng thời tạo ba tài khoản demo và liên kết hồ sơ của họ.
Khi nâng cấp từ tài khoản mặc định dùng email làm username, script đổi username trên cùng tài khoản
để giữ liên kết dữ liệu học. Cập nhật username và mật khẩu trong file local theo mẫu mới khi cần.
Hướng dẫn realm, đồng bộ cấu hình và bootstrap nằm trong [Keycloak](../infra/keycloak/README.md).
Không dùng các tài khoản/mật khẩu mẫu này cho production.

<a id="learner-demo-accounts"></a>
## Tài khoản learner demo

`make setup` tạo các tài khoản demo có quyền `LEARNER` trong realm `lyreo` và liên kết
chúng với hồ sơ Admin. Mật khẩu lúc tạo lấy từ `KEYCLOAK_SEED_LEARNER_PASSWORD`
trong cấu hình Keycloak local, cùng mặc định như tài khoản `learner` ở trên.

| Username | Email | Hồ sơ lúc tạo |
|---|---|---|
| `demo-learner-minh-anh` | minh-anh@example.test | Đã hoàn tất |
| `demo-learner-gia-huy` | gia-huy@example.test | Đã hoàn tất |
| `demo-learner-new` | learner-moi@example.test | Chưa hoàn tất |

Để hỗ trợ đặt lại mật khẩu local, mở Keycloak Admin Console, chọn realm
`lyreo`, mở **Users → Credentials → Reset password** cho tài khoản cần hỗ trợ.
Bật **Temporary** để người dùng đổi mật khẩu khi đăng nhập tiếp theo.
Với tài khoản có email thật, cấu hình SMTP và bật **Forgot password** trong Keycloak
để người dùng tự đặt lại. Admin có thể dùng hành động gửi email **Update Password**
khi SMTP đã sẵn sàng. [Hướng dẫn Keycloak](https://www.keycloak.org/docs/latest/server_admin/#enabling-forgot-password)
