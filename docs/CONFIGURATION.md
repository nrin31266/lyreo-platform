# Lyreo Configuration Model

Tài liệu này mô tả **cấu hình nằm ở đâu, ai được phép thay đổi, giá trị nào là secret và precedence hoạt động thế nào**. Mục tiêu là tránh hai cực đoan:

1. hard-code quá nhiều hành vi sản phẩm khiến mỗi thay đổi phải sửa code; và
2. biến mọi domain invariant thành một bảng `settings(key,value)` khó kiểm soát.

## 1. Nguyên tắc tổng quát

Lyreo có năm tầng cấu hình:

```text
Deployment capability / secret
        ↓
Admin runtime policy
        ↓
Lesson build snapshot
        ↓
Learner persistent preference
        ↓
Current session override
```

Tầng dưới **không được** override giới hạn an toàn của tầng trên.

Ví dụ:

- Admin đặt `sentence IPA = DISABLED` → learner không thể ép server sinh IPA dù setting cá nhân là `ALWAYS`.
- Admin cho phép `ON_DEMAND` → learner có thể chọn `OFF`, `TAP_TO_SHOW`, `ALWAYS`.
- Session có thể đổi playback speed tạm thời nhưng không làm thay đổi default cross-device nếu user không bấm “Save as default”.

## 2. Deployment configuration (`.env` / secret manager)

Đây là các giá trị cần để process **khởi động hoặc kết nối hạ tầng**. Không chỉnh qua Admin UI.

| Nhóm | Ví dụ | File dev | Có được gửi ra frontend? |
|---|---|---|---|
| Database | JDBC URL/user/password | `apps/core-service/.env` | Không |
| Keycloak server/bootstrap | admin password, confidential client secret | `infra/docker/.env`, `infra/keycloak/.env` | Không |
| OIDC public client metadata | issuer URL, public client ID | frontend `.env` | Có |
| R2 | endpoint/access key/secret | `apps/core-service/.env` | Không |
| AI internal auth | Core↔FastAPI token | Core + AI `.env` | Không |
| Encryption root key | `MASTER_ENCRYPTION_KEY` | Core `.env` | Không |
| Runtime | Qwen model/device/dtype | AI `.env` | Không |

### 2.1 Không có root `.env`

Mỗi executable có environment riêng:

```text
infra/docker/.env             Docker infrastructure interpolation
infra/keycloak/.env           local bootstrap scripts
apps/core-service/.env        Spring Boot runtime
services/ai-service/.env      FastAPI/model runtime
apps/admin-web/.env           browser-public build/dev config
apps/mobile/.env              EXPO_PUBLIC_* only
tools/data-import/.env        importer DB/R2/data paths
```

`./scripts/init-dev-env.sh` copy các `.env.example` và đồng bộ những secret local phải giống nhau.

### 2.2 Frontend variables là public

Bất kỳ biến nào bắt đầu bằng `VITE_` hoặc `EXPO_PUBLIC_` đều được xem là **public data**. Không đặt:

- API key Gemini/Groq/DeepSeek;
- R2 secret;
- Keycloak confidential secret;
- database password;
- `MASTER_ENCRYPTION_KEY`.

Repository validator sẽ fail nếu `.env.example` frontend chứa key có tên giống secret/password/API key.

## 3. Admin runtime policy

Những thứ admin nên chỉnh khi hệ thống đang chạy:

- provider/model routing;
- bật/tắt AI provider;
- default lesson processing policy;
- sentence IPA strategy (`DISABLED`, `ON_DEMAND`, `PREGENERATE`);
- default accent;
- feature availability;
- rate/cost policy cho tính năng AI đắt;
- mission/reward configuration có kiểm soát.

### 3.1 Không hard-code model name

Java domain dùng capability:

```text
STT
ALIGNMENT
GENERAL_LLM
REASONING_LLM
TTS
PRONUNCIATION_JUDGE
```

`ai_capability_route` quyết định provider + model + fallback. Vì model đổi nhanh, tên model là string runtime config, không phải Java enum.

### 3.2 API key provider

`ai_provider.encrypted_api_key` lưu ciphertext AES-GCM. `MASTER_ENCRYPTION_KEY` chỉ nằm ngoài DB.

Admin API chỉ trả:

```json
{
  "configured": true,
  "last4": "91F2"
}
```

Không có endpoint đọc lại plaintext key.

## 4. Lesson build snapshot

Khi admin tạo lesson, hệ thống **snapshot config đã dùng** vào `background_job.config_snapshot_json` và `lesson_build_job.provider_snapshot_json`.

Ví dụ:

```json
{
  "activities": ["DICTATION", "SHADOWING"],
  "annotations": {
    "translation": true,
    "lexical": true,
    "grammar": true,
    "sentenceIpa": false,
    "thoughtGroups": true
  },
  "providers": {
    "stt": "QWEN3_ASR",
    "alignment": "QWEN3_FORCED_ALIGNER",
    "llm": "GROQ"
  }
}
```

Lý do snapshot:

- admin đổi provider tháng sau vẫn biết route nào **đang được cấu hình lúc job được accept**;
- build intent/options/step plan của job là durable và không đổi theo config UI về sau;
- audit/debug được bối cảnh routing ban đầu cùng với `ai_invocation` thực tế của từng call.

**Quan trọng:** `provider_snapshot_json` hiện là metadata audit, **không pin execution**. Mỗi AI call vẫn resolve route đang enabled tại thời điểm chạy để operator có thể disable một provider lỗi hoặc đổi fallback cho queued/retry job. `ai_invocation` mới là record provider/model thực tế đã dùng. Nếu sau này cần reproducibility tuyệt đối theo provider/model snapshot, phải chốt một decision riêng thay vì âm thầm đổi semantic này.

## 5. Learner persistent preferences

User preference cross-device nằm trong `learner_profile.preferences_json`, được deserialize thành `LearnerPreferences` typed object.

Các giá trị hiện tại gồm:

- preferred accent;
- translation display timing;
- sentence IPA display timing;
- vocabulary/grammar note timing;
- thought-group/karaoke highlight;
- proper-noun hints;
- playback speed default.

API:

```text
GET /api/v1/learner/preferences
PUT /api/v1/learner/preferences
```

User setting **không quyết định server capability**. Nó chỉ quyết định cách learner muốn trải nghiệm dữ liệu được phép sử dụng.

## 6. Session override

Những trạng thái chỉ tồn tại trong một session học nằm ở React Native local state/store, ví dụ:

- speed tạm thời `0.8x`;
- loop câu hiện tại;
- panel IPA đang mở;
- index câu hiện tại;
- animation state.

Không PUT mọi tap lên backend. Chỉ persist khi user thay đổi preference lâu dài hoặc khi nghiệp vụ cần lưu progress/attempt.

## 7. Storage configuration

### Local dev

```dotenv
STORAGE_MODE=local
LOCAL_STORAGE_ROOT=../../.data/storage
```

Ưu điểm: clone repo là chạy, không cần credential R2.

### R2

```dotenv
STORAGE_MODE=r2
R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...
R2_BUCKET=lyreo-dev
R2_REGION=auto
```

DB lưu **object key**, không lưu signed URL. Signed URL được tạo lúc cần và có TTL ngắn.

## 8. Background job tuning

Core env:

```dotenv
JOB_POLL_INTERVAL_MS=1000
JOB_RECOVER_INTERVAL_MS=30000
JOB_LEASE_SECONDS=60
JOB_CLAIM_BATCH_SIZE=5
```

Nguyên tắc:

- lease phải dài hơn heartbeat jitter bình thường;
- worker heartbeat độc lập với progress callback;
- step phải idempotent hoặc có persisted step guard;
- cancellation là durable DB state, không phải cache flag.

Không tăng `JOB_CLAIM_BATCH_SIZE` lớn chỉ để “nhanh hơn”; starter worker xử lý batch tuần tự. Scale bằng nhiều Core instances hoặc bounded executor sau benchmark.

## 9. Keycloak configuration

Dev có hai nhóm biến:

- `infra/docker/.env`: container bootstrap + DB;
- `infra/keycloak/.env`: script seed/verify.

`init-dev-env.sh` đồng bộ confidential core client secret giữa Keycloak và Core.

Frontend chỉ có realm URL + public client ID. Mobile/Admin dùng Authorization Code + PKCE.

## 10. Cấu hình nào phải ở code?

Không phải mọi thứ đều configurable. Các invariant sau phải nằm trong code/schema:

- score/diamond không được tin từ client;
- diamond ledger phải idempotent;
- một module không đọc repository module khác;
- Flyway là schema owner;
- job fencing/lease semantics;
- auth role checks;
- valid domain transitions;
- signed URL không được persist như canonical URL.

Nếu một thay đổi làm yếu invariant, cần architecture decision chứ không thêm checkbox.

## 11. Checklist khi thêm config mới

Trước khi thêm một setting:

1. Đây là deployment secret, admin policy, learner preference hay session state?
2. Có cần audit/version/snapshot không?
3. Có cần cross-device không?
4. Giá trị có phải secret không?
5. Tầng thấp hơn có được override không?
6. Có default an toàn không?
7. Có cần invalidate cache không?
8. Có cần migration hay chỉ thêm field JSON backward-compatible?
9. README/.env.example đã giải thích biến khó hiểu chưa?
10. Có vô tình biến domain invariant thành config không?
