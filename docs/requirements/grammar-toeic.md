# Yêu cầu Grammar và TOEIC

Import semantics: [Data Pipelines](../DATA_PIPELINES.md).

<a id="grammar"></a>
## Grammar

### FR-GRM-001 — Grammar taxonomy and question bank

Grammar có topic/subtopic/difficulty, bank sets/questions/memberships và
learner attempts/history; question có answer/explanation/translation/vocabulary metadata khi nguồn có.

Tiêu đề có bản tiếng Anh và tiếng Việt. Admin chọn tiêu đề theo ngôn ngữ giao diện,
với bản còn lại làm dự phòng. Bản dịch và lời giải tiếng Việt là nội dung bổ sung tùy chọn.

### BR-GRM-001 — Dataset-first practice

Không mặc định generate Grammar questions. Dataset là nguồn chính; AI chỉ hỗ trợ
classify missing taxonomy, detect lesson grammar hoặc giải thích on-demand/fallback.

### BR-GRM-002 — Context annotation không phải practice

Lesson Grammar annotation có thể link topic và contextual note; Grammar Practice
mới chọn question bank, nhận answer và chấm server-side.

<a id="toeic"></a>
## TOEIC

<a id="fr-toe-001--test-and-drill-content"></a>
### FR-TOE-001 — Full test content and learner practice

Kho TOEIC chứa đề Listening & Reading đầy đủ Part 1–7, passages/questions/options/media và filtering.
Learner chọn luyện Part/full hoặc thi trên cùng đề gốc; attempt và history giữ snapshot phiên.
Content/media được import từ external dataset, không commit vào Git.

### BR-TOE-001 — Server scoring

Server load answer key và tính raw Listening/Reading correct counts; client chỉ
gửi answers. Scaled score chỉ có khi conversion table được duyệt/provenance rõ; hiện để null theo
[OQ-004](open-questions.md#oq-004--toeic-scaled-score-conversion).

### FR-TOE-002 — Attempt completion fact

Successful submit persist attempt/answers/score summary rồi publish fact cho
Analytics/Gamification consumers qua public contract.

### BR-TOE-002 — Media ownership

Structured data vào PostgreSQL, audio/images vào storage; DB giữ object key.

### FR-GRM-002 — Admin taxonomy and shared question editing

Admin sửa topic, subtopic và bank đã nhập, gồm mô tả EN/VI, thứ tự, access và visibility. Admin
có thể điều chỉnh danh sách câu trong bộ và xem trực tiếp đáp án, giải thích để rà soát. Assessment
sở hữu nội dung, options, đáp án, giải thích và revision câu hỏi dùng chung. Grammar sở hữu taxonomy
và membership; TOEIC sở hữu cấu trúc đề/placement. Bộ công bố pin revision câu hỏi; sửa câu chung
chỉ hiện bản cập nhật để Admin chọn áp dụng và công bố lại. Ẩn membership chỉ đổi đường truy cập đó.
Rà soát kiểm tra catalog, taxonomy, membership và nội dung câu đang tham chiếu: câu trống,
lựa chọn/đáp án, trùng key, ngữ cảnh bắt buộc, bản dịch/giải thích và quyền hiển thị. Mỗi issue có
mức độ, mã, vị trí và đường mở đúng câu/trường. Không đổi ngầm sang revision mới. Báo cáo import
bất biến ở Nguồn dữ liệu để đối chiếu, tách với báo cáo chất lượng hiện tại.

Ẩn topic chặn discovery con nhưng giữ policy riêng của con. Bulk tối đa 100 mục, có preview ảnh hưởng.
Danh sách chủ đề dẫn tới các chủ đề con đã lọc theo cha.
Editor tách Thông tin và Câu hỏi; chỉ tải danh sách câu khi mở tab câu hỏi. Membership dùng bảng gọn,
mở rà soát từng câu khi cần. Bỏ membership không xóa câu hỏi gốc hoặc các nơi sử dụng khác.

### FR-TOE-003 — Admin full tests and passage quality

Admin rà soát và biên tập đề đã import. Công cụ CLI cài gói dữ liệu chuẩn bị sẵn.
Đề đầy đủ có đúng 200 câu: 100 Listening + 100 Reading, theo Part `6/25/39/30/30/16/54`.
Part 2 có đúng lựa chọn A/B/C, các Part còn lại có A/B/C/D; nhóm Part 3/4 có 3 câu, Part 6 có 4. Part 7 có 29 câu đọc đơn (2–4 câu/nhóm) và
25 câu đọc nhiều tài liệu (5 câu với 2–3 tài liệu/nhóm).
Câu CLOZE Part 6 được phép thiếu stem riêng nhưng phải có passage và số gap duy nhất trong nhóm;
số gap giữ nguyên trong lịch sử phiên. Ngân hàng chung hiển thị ngữ cảnh đã import
và dẫn thẳng đến câu trong đề. Câu ảnh Part 1 có thể dùng ảnh/audio thay cho câu dẫn.
Câu cần passage/audio nhóm
không được chọn vào bộ Grammar độc lập.
Admin chỉ quản lý đề đầy đủ, không tạo/lưu drill, trích Part, đổi số câu hay di chuyển câu sang Part khác.
Server từ chối nội dung loại DRILL và bản lưu không đủ 200 câu/đúng phân bố Part.
Khôi phục bản cũ cũng phải đạt cấu trúc 200 câu và kiểm tra lỗi bắt buộc trước khi áp dụng.
Luyện từng Part, full hoặc thi là lựa chọn trong phiên của người học trên cùng đề gốc; không tạo thêm catalog đề.
Các drill biên tập cũ không xuất hiện trong catalog mới; lịch sử phiên được giữ.
Editor hỗ trợ Part 1–7, 1–3 tài liệu/nhóm, bảng,
transcript/dịch, ảnh/audio và rà soát đầy đủ đáp án/lời lựa chọn cho Admin. HTML nguồn
được giữ để so sánh; conversion không được âm thầm mất cấu trúc. HTML biên tập được sanitize.

Báo cáo rà soát nguồn là snapshot tại lúc import, giữ nguyên để đối chiếu; không phải trạng thái sửa
lỗi hiện tại. Nhóm TOEIC có lỗi tách tài liệu có link tới đúng đề/Part/nhóm để sửa. Kiểm tra đề
chạy trên bản lưu hiện tại, phân biệt lỗi bắt buộc sửa và cảnh báo nội dung hỗ trợ; sửa chưa lưu
làm kết quả cũ không còn áp dụng. Kiểm tra storage lỗi/hết thời gian phải hiện lỗi và cho thử lại;
không hiển thị trạng thái đạt từ lần kiểm tra cũ hoặc tự lặp request.
Không có checkbox xác nhận để vượt qua lỗi cấu trúc. Báo cáo gắn revision/fingerprint dependency,
phiên bản luật và thời điểm; sửa nội dung làm báo cáo cũ hết hiệu lực. Trạng thái gồm UNCHECKED,
RUNNING, PASSED (luật tự động), WARNING, BLOCKED, STALE và FAILED; chưa chạy/timeout không là đạt.

Publish nguyên tử pin câu hỏi/tài liệu/media. Thiếu media bắt buộc, sai option/answer, group/Part,
số câu hoặc passage chặn publish. Thiếu dịch/giải thích là cảnh báo; tài liệu nguồn bất thường cần
sửa có bằng chứng. Reading vẫn có hình trong tài liệu, nhưng không được gắn audio Listening.
Câu và AI được mở trong ngữ cảnh đề/Part với passage/transcript đã lưu hiện tại. Nháp/publication/history tuân thủ
[BR-LEX-003](lexicon-vocabulary.md#br-lex-003--editorial-publication).

### BR-ASM-001 — Pinned learner sessions

Luyện Grammar và TOEIC dùng lifecycle bắt đầu → lưu đáp án → nộp → kết quả/history. Server pin
publication, đáp án và media lúc bắt đầu; thay đáp án, đổi release hoặc ẩn nội dung không đổi phiên
đã mở. Không bắt đầu mới từ nội dung không còn khả dụng. Phiên được tiếp tục trong 24 giờ; đây
không phải chế độ thi có giám sát. Ownership được kiểm tra trên mọi thao tác/media; discovery không
lộ đáp án hoặc storage key. Submit idempotent, bỏ trống tính sai, completion fact chỉ phát một lần.
Raw score do server tính; scaled score vẫn null. Lịch sử cũ được giữ nguyên trong đợt cutover API
trước sản xuất. Không xây Mobile UI trong đợt Admin này.

## User Stories & Acceptance Criteria

<a id="us-grm-001--luyen-grammar-tu-question-bank"></a>
### US-GRM-001 — Luyện Grammar từ question bank

Là learner, tôi muốn trả lời question có taxonomy/explanation để luyện đúng topic.

#### AC-GRM-001 — Server-checked answer

Given question tồn tại, when learner submit option , then server
load answer key, persist attempt, publish a grammar answer fact và trả correct/explanation phù hợp; client không quyết định correctness.

#### AC-GRM-002 — Missing/invalid question

Given question hoặc option không hợp lệ, when submit, then không tạo successful attempt/event; AI
không tự generate câu thay thế mặc định.

<a id="us-toe-001--nop-toeic-attempt"></a>
### US-TOE-001 — Nộp TOEIC attempt

Là learner, tôi muốn nộp answers để nhận Listening/Reading result và history chính xác.

#### AC-TOE-001 — Raw scoring

Given active test và answer key, when submit , then server persist answers,
tính raw correct counts và publish a TOEIC completion fact; missing answers được xử lý theo service contract.

#### AC-TOE-002 — Scaled score honesty

Given chưa có conversion table được duyệt, when trả score, then scaled Listening/Reading fields là
null và UI không diễn giải raw counts thành official scaled score (xem [OQ-004](open-questions.md#oq-004--toeic-scaled-score-conversion)).
