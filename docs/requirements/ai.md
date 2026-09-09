# Yêu cầu AI administration và execution

Nguồn migration: đặc tả Lyreo trước khi tách owner; boundary kỹ thuật: [AI execution](../architecture/ai-execution.md);
workflow: [AI routing](../features/ai-routing.md).

### BR-AI-001 — Java sở hữu business AI intent

Status: inherited. Java/Core quyết định lý do/thời điểm gọi, product prompt, expected schema,
orchestration, route, retry/fallback, persistence và state transition.

### FR-AI-001 — Capability execution

Status: inherited. FastAPI cung cấp STT, alignment, TTS, NLP analysis, generic LLM generation và
multimodal judging qua capability endpoints; không có Lesson/Curriculum/Reward/SRS/TOEIC workflow.

### FR-AI-002 — Configurable routing and fallback

Status: inherited. Admin có thể cấu hình provider, model string, enabled state, priority/fallback và
rate/cost policy theo capability. Model name không khóa vào Java enum.

### BR-AI-002 — Routing time and audit snapshot

Status: inherited. Snapshot khi accept job phục vụ audit context; execution resolve route đang
enabled và invocation lưu actual provider/model. Snapshot không hứa pin provider để tái lập output.

### FR-AI-003 — Invocation audit

Status: inherited. Audit liên kết capability/provider/model/status/timing/token/cost khi có,
request hash, artifact keys và stable error code; không lưu secret.

### BR-AI-003 — Provider credential confidentiality

Status: inherited. Master encryption key nằm ở environment; provider key mã hóa AES-GCM trong Core.
Browser chỉ nhận configured/last4/status, không plaintext. FastAPI không persist credential.

### FR-AI-004 — Runtime modes

Status: inherited. `mock` hỗ trợ dev/CI deterministic không GPU/paid provider; `local` lazy-load
Qwen ASR/alignment. SaaS provider routing độc lập với runtime mode; `remote` chưa được implement.

### BR-AI-004 — Raw versus normalized output

Status: inherited. Raw provider output là artifact debug/audit trong object storage; normalized
queryable result và workflow checkpoint ở PostgreSQL. Cancellation được kiểm tra trước commit.
