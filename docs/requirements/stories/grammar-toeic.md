# Stories — Grammar và TOEIC

Requirements: [Grammar/TOEIC](../grammar-toeic.md).

### US-GRM-001 — Luyện Grammar từ question bank

Là learner, tôi muốn trả lời question có taxonomy/explanation để luyện đúng topic.

#### AC-GRM-001 — Server-checked answer

Given question tồn tại, when learner submit option, then server load answer key, persist attempt và
trả correct/explanation phù hợp; client không quyết định correctness.

#### AC-GRM-002 — Missing/invalid question

Given question hoặc option không hợp lệ, when submit, then không tạo successful attempt/event; AI
không tự generate câu thay thế mặc định.

### US-TOE-001 — Nộp TOEIC attempt

Là learner, tôi muốn nộp answers để nhận Listening/Reading result và history chính xác.

#### AC-TOE-001 — Raw scoring

Given active test và answer key, when submit, then server persist answers, tính raw correct counts và
publish completion fact; missing answers được xử lý theo service contract.

#### AC-TOE-002 — Scaled score honesty

Given chưa có conversion table được duyệt, when trả score, then scaled Listening/Reading fields là
null và UI không diễn giải raw counts thành official scaled score.
