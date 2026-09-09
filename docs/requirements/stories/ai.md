# Stories — AI administration và execution

Requirements: [AI](../ai.md).

### US-AI-001 — Quản lý route provider

Là Admin, tôi muốn cấu hình provider/model/fallback theo capability mà không lộ credential.

#### AC-AI-001 — Credential write/read

Given Admin gửi key qua protected endpoint, when lưu/read settings, then server mã hóa key và response
chỉ trả metadata masked/configured, không plaintext.

#### AC-AI-002 — Live route resolution

Given route thay đổi sau khi job accepted, when step execute, then Core resolve route đang enabled,
ghi actual provider/model và vẫn giữ acceptance snapshot cho audit.

### US-AI-002 — Thực thi capability có audit

Là workflow owner, tôi muốn gọi capability bằng contract thống nhất để retry/cancel và truy vết result.

#### AC-AI-003 — Authorized capability call

Given internal token và request schema hợp lệ, when Core gọi FastAPI capability, then adapter thực
thi và trả normalized response; invocation audit ghi status/timing/artifact keys cần thiết.

#### AC-AI-004 — Failure/cancellation

Given timeout/provider error/cancel, when call kết thúc, then policy phân loại retry, không expose raw
error cho client, và không commit output nếu job không còn quyền tiếp tục.
