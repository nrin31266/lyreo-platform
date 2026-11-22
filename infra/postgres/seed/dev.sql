-- Development defaults only. Re-running preserves existing names and translations.
BEGIN;
INSERT INTO lesson_category(id,name,translations) VALUES
('20000000-0000-0000-0000-000000000001','Daily conversation','{"vi":"Giao tiếp hằng ngày"}'),
('20000000-0000-0000-0000-000000000002','Travel','{"vi":"Du lịch"}'),
('20000000-0000-0000-0000-000000000003','Work','{"vi":"Công việc"}'),
('20000000-0000-0000-0000-000000000004','News','{"vi":"Tin tức"}'),
('20000000-0000-0000-0000-000000000005','Science & technology','{"vi":"Khoa học & công nghệ"}'),
('20000000-0000-0000-0000-000000000006','TOEIC preparation','{"vi":"Luyện thi TOEIC"}'),
('20000000-0000-0000-0000-000000000007','Food & cooking','{"vi":"Ẩm thực & nấu ăn"}'),
('20000000-0000-0000-0000-000000000008','Health & wellbeing','{"vi":"Sức khoẻ & tinh thần"}'),
('20000000-0000-0000-0000-000000000009','Education','{"vi":"Giáo dục"}'),
('20000000-0000-0000-0000-000000000010','Nature & environment','{"vi":"Thiên nhiên & môi trường"}'),
('20000000-0000-0000-0000-000000000011','Business & finance','{"vi":"Kinh doanh & tài chính"}'),
('20000000-0000-0000-0000-000000000012','Sports & fitness','{"vi":"Thể thao & vận động"}'),
('20000000-0000-0000-0000-000000000013','Arts & entertainment','{"vi":"Nghệ thuật & giải trí"}'),
('20000000-0000-0000-0000-000000000014','Family & relationships','{"vi":"Gia đình & các mối quan hệ"}'),
('20000000-0000-0000-0000-000000000015','Shopping & services','{"vi":"Mua sắm & dịch vụ"}'),
('20000000-0000-0000-0000-000000000016','Home & daily life','{"vi":"Nhà cửa & sinh hoạt"}'),
('20000000-0000-0000-0000-000000000017','Personal development','{"vi":"Phát triển bản thân"}'),
('20000000-0000-0000-0000-000000000018','Stories & literature','{"vi":"Truyện & văn học"}'),
('20000000-0000-0000-0000-000000000019','Culture & society','{"vi":"Văn hoá & xã hội"}'),
('20000000-0000-0000-0000-000000000020','History','{"vi":"Lịch sử"}'),
('20000000-0000-0000-0000-000000000021','Hobbies & leisure','{"vi":"Sở thích & giải trí"}'),
('20000000-0000-0000-0000-000000000022','Weather','{"vi":"Thời tiết"}'),
('20000000-0000-0000-0000-000000000023','IELTS preparation','{"vi":"Luyện thi IELTS"}'),
('20000000-0000-0000-0000-000000000024','Academic English','{"vi":"Tiếng Anh học thuật"}')
ON CONFLICT DO NOTHING;
COMMIT;
