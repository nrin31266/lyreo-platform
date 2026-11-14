-- Development defaults only. Re-running preserves existing names and translations.
BEGIN;
INSERT INTO lesson_category(id,name,translations) VALUES
('20000000-0000-0000-0000-000000000001','Daily conversation','{"vi":"Giao tiếp hằng ngày"}'),
('20000000-0000-0000-0000-000000000002','Travel','{"vi":"Du lịch"}'),
('20000000-0000-0000-0000-000000000003','Work','{"vi":"Công việc"}'),
('20000000-0000-0000-0000-000000000004','News','{"vi":"Tin tức"}'),
('20000000-0000-0000-0000-000000000005','Science & technology','{"vi":"Khoa học & công nghệ"}'),
('20000000-0000-0000-0000-000000000006','TOEIC preparation','{"vi":"Luyện thi TOEIC"}')
ON CONFLICT DO NOTHING;
COMMIT;
