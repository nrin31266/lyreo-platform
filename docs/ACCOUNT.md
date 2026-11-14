# Tài khoản development local

Tra cứu nhanh các tài khoản mẫu sau khi chạy `make setup` hoặc `make keycloak-seed`.
Các mật khẩu dưới đây là mặc định công khai dành riêng cho local development.

| Dùng cho | Địa chỉ | Tài khoản | Mật khẩu mặc định | Quyền |
|---|---|---|---|---|
| Lyreo Admin Web | [localhost:5173](http://localhost:5173) | `admin@local.test` | `Password123!ChangeMe` | `ADMIN`, `LEARNER` trong realm `lyreo` |
| Lyreo learner / Mobile | Realm `lyreo` tại [localhost:8081](http://localhost:8081/realms/lyreo) | `learner@local.test` | `Password123!ChangeMe` | `LEARNER` |
| Keycloak Admin Console | [localhost:8081/admin](http://localhost:8081/admin/) | `admin` | `admin` | Quản trị Keycloak trong realm `master` |

Đăng nhập Admin Web: mở trang, bấm **Đăng nhập không gian làm việc**, rồi dùng
`admin@local.test`. Tài khoản `admin` dành cho Keycloak Admin Console.

Giá trị mặc định được định nghĩa trong
[`infra/keycloak/.env.example`](../infra/keycloak/.env.example). Nếu máy đã đổi tài khoản hoặc
mật khẩu, xem file local `infra/keycloak/.env`; cấu hình bootstrap admin của container thuộc
`infra/docker/.env`. Không đưa mật khẩu riêng, token bootstrap hay client secret vào tài liệu này.

Khi cần tạo tài khoản mẫu trên môi trường local đã chuẩn bị:

```bash
make keycloak-seed
```

Lệnh này tạo/cập nhật hai tài khoản Lyreo, đặt lại mật khẩu theo `infra/keycloak/.env` và gán quyền.
Hướng dẫn realm, đồng bộ cấu hình và bootstrap nằm trong [Keycloak](../infra/keycloak/README.md).
Không dùng các tài khoản/mật khẩu mẫu này cho production.
