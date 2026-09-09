# Quy ước tài liệu Lyreo

Mục đích: quy định ownership, ID, liên kết và bằng chứng khi tạo/sửa docs. Chỉ cần đọc file này khi
công việc có tác động tài liệu. Bắt đầu chọn owner từ [bảng định tuyến](README.md).

## Một thông tin, một owner

| Thông tin | Owner chuẩn |
|---|---|
| Lý do sản phẩm và bằng chứng người dùng | `product/discovery.md` |
| Scope, ưu tiên, non-goals, kết quả mong muốn | `product/prd.md` |
| Nghĩa vụ sản phẩm/kinh doanh/phi chức năng | `requirements/*.md` |
| Điều kiện chấp nhận quan sát được | `requirements/stories/*.md` |
| Flow, state, error, side effect của workflow | `features/*.md` |
| Engineering invariants | `AGENTS.md` |
| Protocol/rationale kỹ thuật | `ARCHITECTURE.md`, `architecture/*.md`, `DECISIONS.md` |
| Config/data/runbook | `CONFIGURATION.md`, `DATA_PIPELINES.md`, `DEVELOPMENT.md`, `OPERATIONS.md` |
| Trạng thái kiểm chứng | `requirements/traceability.md` |
| Câu hỏi/xung đột/khoảng trống | `requirements/gaps.md` |

Nơi khác được viết một câu định hướng và link. Không sao chép toàn bộ rule, AC, endpoint/schema,
config hay roadmap. `LYREO_PLATFORM_SPEC.md` chỉ giữ tương thích đường dẫn/anchor cũ.

## Trạng thái và nguồn

- Requirement decision status: `inherited`, `proposed`, `approved`, `superseded`.
- `inherited` chỉ nói ý được chuyển từ master cũ; không tự chứng nhận đã được chủ dự án phê duyệt.
- Evidence status: `not-run`, `partial`, `implemented-unverified`, `verified`.
- `verified` phải nêu command/source, ngày và commit hoặc phạm vi chạy xác định.
- Phân biệt `Bằng chứng`, `Giả thuyết`, `Quyết định`; không dựng persona, quote, KPI hay kết quả test.
- Wire contract hiện có nên link controller/DTO/schema; không chép toàn bộ code/SQL vào docs.

## ID ổn định

Definition dùng heading bắt đầu trực tiếp bằng ID:

```markdown
### FR-LSN-001 — Lesson source
#### AC-LSN-001 — Chấp nhận build
# FEAT-LESSON-BUILD — Lesson Build
```

Prefix dùng trong repository:

- `FR-<AREA>-NNN`: functional requirement;
- `BR-<AREA>-NNN`: business rule;
- `NFR-<AREA>-NNN`: non-functional requirement;
- `US-<AREA>-NNN`: user story; `AC-<AREA>-NNN`: acceptance criterion;
- `FEAT-<SLUG>`: feature contract; `GAP-NNN`: gaps register;
- `D-NNN`: decision log; giữ nguyên D-001–D-019.

Area codes hiện dùng: `IDN`, `LSN`, `AI`, `LEX`, `VOC`, `GRM`, `TOE`, `CUR`, `GAM`, `ANL`,
`NTF`, `CHT`, `SEC`, `OPS`, `DAT`, `UI`. Không renumber ID để chèn nội dung; không tái dùng ID
superseded cho nghĩa mới. Reference dùng link tới definition khi có thể.

## Link, path và anchor

- Dùng relative Markdown links, đúng case trên Linux.
- Ưu tiên heading ổn định hoặc explicit anchor khi compatibility quan trọng.
- Dùng đường dẫn file thật; `…` chỉ được dùng trong prose minh họa, không làm evidence path.
- Path dự kiến phải gắn `planned`; không được dùng làm evidence implementation.
- Link ngoài hỗ trợ phương pháp, không xác nhận feature Lyreo.
- Không dẫn máy scratch, home directory hoặc plan ngoài repository.

Checker hỗ trợ inline links, reference-style links, heading fragments theo GitHub-style subset,
explicit HTML anchors, fenced code exclusion, ID definitions và một số trường path có cấu trúc.
Nó không phải Markdown parser tổng quát và không phát hiện duplicate semantics; review nội dung vẫn
bắt buộc.

## Luồng cập nhật

Xác định owner/ID → so intent với code → phân loại thay đổi → lấy quyết định nếu cần → cập nhật owner
và AC → implementation/tests trong phạm vi → traceability/evidence → chạy link/route checker.

Khi rename/move, sửa inbound references và giữ compatibility anchor/stub nếu cần. Khi bỏ requirement,
ghi supersession và tác động; khi thêm feature, chỉ thêm route/ID sau khi đã có owner thật.

Gap gồm ID, type (`question`, `conflict`, `implementation-gap`, `verification-gap`), mô tả, nguồn,
tác động, bước kế tiếp và trạng thái. Chỉ gọi “confirmed bug” khi có chứng cứ phù hợp.

## Kiểm tra

```bash
make validate-docs
python3 -m unittest tooling.tests.test_validate_docs
```

CI/link checker chứng minh cấu trúc liên kết và convention, không chứng minh requirement đúng hay
feature end-to-end hoàn thiện.
