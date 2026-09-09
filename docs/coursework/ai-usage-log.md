# AI usage log — documentation setup

Mục đích: bằng chứng trung thực về AI hỗ trợ trong đợt migration docs đầu tiên. Không phải transcript
đầy đủ, đánh giá chất lượng model hay human approval.

## 2026-09-09 — Setup documentation ownership and drift checks

- Công cụ: Codex coding agent; model name/version cụ thể không được giao diện task cung cấp để xác nhận.
- Nhiệm vụ: đọc execution plan do người dùng cung cấp, kiểm kê repository ở commit
  `58ca5e7e6f960b4e3f15ddb165fb3169027d7e20`, chuyển master spec sang owner docs, tạo routing,
  requirements/stories/features/coursework, traceability/gaps và offline docs validator.
- Input: root `AGENTS.md`, 21 project docs, code/test/config entrypoints liên quan và execution plan
  ngoài repository. Plan ngoài repo không được biến thành tài liệu bắt buộc đọc về sau.
- Output: `AGENTS.md`, `docs/README.md`, `docs/documentation.md`, `docs/product/`,
  `docs/requirements/`, `docs/features/`, `docs/architecture/`, `docs/coursework/`, compatibility page,
  validator/tests và CI/Makefile wiring.
- Kiểm tra của AI: `make validate-docs` (`Markdown=62`), `make validate`
  (`Java=208 Python=24 TS/TSX=49 SQL=8`), 6 checker fixtures, `git diff --check`, master heading
  comparison `68/68`, alias/CI wiring checks và sáu routing simulations đều chạy exit 0 trong đợt
  setup. Các gate này không bao gồm Java suite, frontend build/typecheck, native/Docker hay live AI.
- Human review còn cần: product scope/YouTube, Dictation threshold, KPI/NFR thresholds, privacy/
  retention, TOEIC score source, Shadowing UX/wiring và mọi mục open trong gaps register.

AI đã hỗ trợ tổ chức, đối chiếu và draft. AI không thực hiện user research, không gọi paid model,
không tải dataset, không chạy native/live-provider integration và không có quyền tự ký approval sản phẩm.
