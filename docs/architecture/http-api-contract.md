# Core HTTP API Contract

Mục đích: owner duy nhất của quy chuẩn HTTP API công khai cho Lyreo Core Service, bao gồm cấu trúc
response thành công, RFC 9457 Problem Details cho lỗi, correlation ID, authentication/authorization
error contract, rate limiting, Bean Validation ở HTTP boundary, và cấu hình OpenAPI/Swagger.

---

## 1. Success response contract

Lyreo **không sử dụng** global wrapper dạng `{ "success": true, "data": ... }`. Thay vào đó, API
trả trực tiếp resource hoặc typed DTO.

### Mã HTTP status thành công

| Status code | Ý nghĩa | Ví dụ áp dụng |
|---|---|---|
| `200 OK` | Query hoặc update thành công có body | `GET /api/v1/jobs/{id}`, `GET /api/v1/me`, `PUT /api/v1/learner/preferences` |
| `201 Created` | Tạo mới tài nguyên synchronous có body | Tạo mới tài nguyên tức thì kèm thông tin đối tượng |
| `202 Accepted` | Tiếp nhận tác vụ async thành công | `POST /api/v1/admin/lessons/build` (kèm header `Location: /api/v1/jobs/{jobId}` và body ticket `BuildAcceptedResponse(lessonId, jobId)`), `POST /api/v1/jobs/{id}/cancel` (empty body) |
| `204 No Content` | Thao tác thành công, không trả body | `POST /internal/dev/bootstrap/users` |

---

## 2. Error response contract — RFC 9457

Mọi lỗi trả về từ Core public HTTP API (`/api/v1/**`) bắt buộc tuân thủ chuẩn RFC 9457 Problem Details.

- `Content-Type`: `application/problem+json`
- Shape canonical:

```json
{
  "type": "urn:lyreo:problem:request-validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "/api/v1/admin/lessons/build",
  "code": "REQUEST_VALIDATION_FAILED",
  "correlationId": "c8b417e0-47b2-4d2d-8b65-6b3d11b3e8e9",
  "errors": [
    {
      "field": "title",
      "code": "NotBlank",
      "message": "must not be blank"
    }
  ]
}
```

### Các trường chuẩn

- `type`: URI định danh loại lỗi (`urn:lyreo:problem:<slug>`).
- `title`: Tóm tắt ngắn bằng tiếng Anh tương ứng loại lỗi.
- `status`: HTTP status code nguyên thủy.
- `detail`: Mô tả an toàn, có ý nghĩa cho client/developer; không leak chi tiết nội bộ.
- `instance`: URI path của request hiện tại.
- `code`: Mã lỗi định danh dạng UPPER_SNAKE_CASE ổn định cho máy tính xử lý.
- `correlationId`: Mã định danh tương quan khớp với header `X-Correlation-Id` và server log.
- `errors`: Mảng các vi phạm chi tiết (chỉ xuất hiện khi có validation violations).

### Bảng mã lỗi chuẩn ban đầu

| Code | Status | Ý nghĩa |
|---|---|---|
| `REQUEST_VALIDATION_FAILED` | 400 | Vi phạm validation Bean Validation trên request body hoặc parameters |
| `MALFORMED_REQUEST` | 400 | Request body JSON không parse được hoặc sai cấu trúc cú pháp |
| `AUTHENTICATION_REQUIRED` | 401 | Thiếu hoặc token không hợp lệ; yêu cầu Bearer authentication |
| `ACCESS_DENIED` | 403 | Người dùng đã xác thực nhưng thiếu quyền hạn cần thiết |
| `RESOURCE_NOT_FOUND` | 404 | Tài nguyên được yêu cầu theo ID không tồn tại |
| `STATE_CONFLICT` | 409 | Xung đột trạng thái nghiệp vụ (ví dụ trùng lặp trạng thái, vi phạm điều kiện chuyển đổi) |
| `METHOD_NOT_ALLOWED` | 405 | Phương thức HTTP không được hỗ trợ trên endpoint |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Content-Type của request không được endpoint chấp nhận |
| `RATE_LIMITED` | 429 | Vượt quá quota lượt gọi cho phép; kèm header `Retry-After` |
| `INTERNAL_ERROR` | 500 | Lỗi máy chủ không lường trước; che giấu thông tin nhạy cảm |

---

## 3. Quy tắc bắt và che giấu ngoại lệ

1. **Tuyệt đối không map toàn cục generic Java exceptions**:
   - Không ánh xạ chung `IllegalArgumentException -> 400`.
   - Không ánh xạ chung `IllegalStateException -> 409`.
2. **Che giấu lỗi hệ thống (Safe 500)**:
   - Các lỗi bất ngờ (cơ sở dữ liệu, mã hóa, lỗi mạng, lỗi file, null pointer, stack trace) phải được bắt bởi fallback handler và trả về HTTP `500` với mã `INTERNAL_ERROR`.
   - Client chỉ nhận thông điệp an toàn: `"An unexpected server error occurred."`.
   - Server ghi log đầy đủ kèm stack trace và `correlationId` để truy vết.
3. **Ngoại lệ nghiệp vụ có chủ đích**:
   - Khi cần trả về lỗi 404, dùng exception có ngữ nghĩa rõ như `ResourceNotFoundException`.
   - Khi cần trả về lỗi 409, dùng `StateConflictException` hoặc exception đặc tả của module.
   - Validation cú pháp được xử lý bởi Bean Validation ở HTTP layer.

---

## 4. Correlation ID

Hệ thống sử dụng một nguồn duy nhất cho correlation ID xuyên suốt request lifecycle:

1. Request đến kèm header `X-Correlation-Id`.
2. `CorrelationIdFilter` chạy ở thứ tự ưu tiên cao nhất (`Ordered.HIGHEST_PRECEDENCE`):
   - Kiểm tra và sanitize: chấp nhận chuỗi độ dài từ 1 đến 64 ký tự, chỉ gồm ký tự chữ, số, dấu gạch ngang `-`, gạch dưới `_`.
   - Nếu header thiếu hoặc không hợp lệ: sinh UUID ngẫu nhiên mới.
   - Lưu vào request attribute: `CorrelationIdAccessor.CORRELATION_ID_ATTRIBUTE`.
   - Đặt vào SLF4J `MDC.put("correlationId", id)`.
   - Đặt vào response header `X-Correlation-Id`.
3. Filter giải phóng `MDC.remove("correlationId")` trong khối `finally`.
4. Khi sinh ProblemDetail (tại Exception Handler, Security EntryPoint, RateLimitFilter), trường `correlationId` đọc từ `CorrelationIdAccessor` để đảm bảo luôn trùng khớp với header và log.

---

## 5. Security error contract (401 / 403)

Khi request bị từ chối bởi tầng Spring Security trước khi vào controller:

### 401 Authentication Required

- HTTP Status: `401 Unauthorized`
- `Content-Type`: `application/problem+json`
- Header: `WWW-Authenticate: Bearer error="invalid_token", error_description="..."` (kế thừa từ `BearerTokenAuthenticationEntryPoint`)
- Body ProblemDetail:
  - `code`: `AUTHENTICATION_REQUIRED`
  - `title`: `Authentication required`
  - `detail`: Thông điệp mô tả an toàn hoặc từ auth exception
  - `correlationId`: Đồng nhất với request

### 403 Access Denied

- HTTP Status: `403 Forbidden`
- `Content-Type`: `application/problem+json`
- Body ProblemDetail:
  - `code`: `ACCESS_DENIED`
  - `title`: `Access denied`
  - `detail`: `"Access is denied to this resource."`
  - `correlationId`: Đồng nhất với request

---

## 6. Rate limiting (429)

Khi request vượt quá token bucket:

- HTTP Status: `429 Too Many Requests`
- `Content-Type`: `application/problem+json`
- Header: `Retry-After: <seconds>` (tính từ thời gian nạp lại token thực tế qua `ConsumptionProbe.getNanosToWaitForRefill()`)
- Body ProblemDetail:
  - `code`: `RATE_LIMITED`
  - `title`: `Rate limit exceeded`
  - `detail`: `"Too many requests. Please try again in X seconds."`
  - `correlationId`: Đồng nhất với request

---

## 7. Bean Validation ở HTTP boundary

- Request body DTO sử dụng Java records với các annotation Bean Validation tiêu chuẩn (`jakarta.validation.constraints.*`):
  - `@NotBlank`, `@NotNull`, `@Min`, `@Max`, `@Size`.
- Controller phương thức khai báo `@Valid @RequestBody`.
- Bean Validation chỉ kiểm tra tính hợp lệ cú pháp, định dạng và ràng buộc trường rõ ràng của payload.
- Quy tắc nghiệp vụ (ví dụ: quyền hạn, quota, trạng thái hợp lệ của entity) thuộc về application/domain services.

---

## 8. HTTP DTO boundary

- Các endpoint công khai của Core Service trả về typed Java record DTOs thay vì `Map<String, Object>` hoặc `Object`.
- Domain/application entity không được expose trực tiếp ra ngoài nếu làm rò rỉ chi tiết persistence hoặc gây schema OpenAPI không ổn định.
- `POST /api/v1/admin/lessons/build`: trả typed record `BuildAcceptedResponse` (`lessonId`, `jobId`), không serialize internal `LessonBuildPlan`. Tiến độ build được theo dõi bất đồng bộ qua `Location: /api/v1/jobs/{jobId}`.
- `POST /api/v1/jobs/{id}/cancel`: trả `202 Accepted` với empty body. Lỗi (nếu có: 404 `RESOURCE_NOT_FOUND`, 409 `STATE_CONFLICT`) tuân thủ RFC 9457 `LyreoProblemDetail`.
- Các payload động đặc thù (như AI raw JSON artifact lưu trữ) được giữ nguyên theo tính chất dữ liệu.

---

## 9. OpenAPI / Swagger

- Công cụ: `springdoc-openapi-starter-webmvc-ui`.
- Endpoints phát triển:
  - `/v3/api-docs`
  - `/v3/api-docs.yaml`
  - `/swagger-ui.html`
- Security scheme: HTTP Bearer JWT (`BearerAuth`).
- Quản lý kích hoạt theo Spring profile:
  - `dev` / `test`: Kích hoạt mặc định.
  - `prod`: Tắt mặc định (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`), cho phép bật qua cấu hình môi trường có kiểm soát.

### Component schemas

Lyreo đăng ký hai programmatic component schemas trong OpenAPI spec:

- `LyreoProblemDetail`: RFC 9457 Problem Details payload mở rộng với `code`, `correlationId`, `errors`.
- `ApiFieldViolation`: Chi tiết vi phạm validation trên từng field.

Schema được đặt tên `LyreoProblemDetail` (không phải `ProblemDetail`) để tránh collision với
`org.springframework.http.ProblemDetail` mà springdoc có thể tự sinh từ return-type scanning.
Constants `PROBLEM_SCHEMA_NAME`, `PROBLEM_SCHEMA_REF`, `VIOLATION_SCHEMA_NAME`, `VIOLATION_SCHEMA_REF`
trong `OpenApiConfiguration` được dùng cho toàn bộ `$ref` pointer.

### Schema registration lifecycle

Schemas được đăng ký bên trong `OpenApiCustomizer` (method `ensureProblemSchemas`) — cùng lifecycle
phase mà `$ref` pointer được thêm vào operations. Điều này ngăn springdoc pruning schema definitions
chưa có reference tại thời điểm bean `OpenAPI` được tạo.

Ngoài ra, property `springdoc.remove-broken-reference-definitions=false` được set trong
`application.yml` như belt-and-suspenders bảo vệ thêm.

---

## 10. CORS and Exposed Headers

- **Allowed headers**: `Authorization`, `Content-Type`, `X-Correlation-Id`.
- **Exposed headers**: `X-Correlation-Id`, `Location`, `Retry-After`.
  - `X-Correlation-Id`: Cho phép client đọc correlation ID để hỗ trợ logging và báo lỗi người dùng.
  - `Location`: Cho phép client lấy URL tác vụ nền khi nhận phản hồi async `202 Accepted` (`POST /api/v1/admin/lessons/build`).
  - `Retry-After`: Cho phép client xác định thời gian chờ (giây) trước khi thử lại khi nhận `429 Too Many Requests`.
