-- Development defaults only. Re-running preserves existing settings and credentials.
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

-- Retire the old local speech routes. Invocation history stores the provider code separately.
DELETE FROM ai_capability_route WHERE provider_id IN (
    SELECT id FROM ai_provider WHERE code IN ('QWEN', 'LOCAL_QWEN', 'QWEN3', 'QWEN3_ASR', 'QWEN3_FORCED_ALIGNER')
);
DELETE FROM ai_capability_route WHERE capability='STT' AND provider_id IN (
    SELECT id FROM ai_provider WHERE code='GROQ'
);
DELETE FROM ai_provider WHERE code IN ('QWEN', 'LOCAL_QWEN', 'QWEN3', 'QWEN3_ASR', 'QWEN3_FORCED_ALIGNER');

-- External providers are prepared for Admin setup with no credentials and no execution.
INSERT INTO ai_provider (id, code, display_name, base_url, enabled) VALUES
        (gen_random_uuid(), 'LOCAL_WHISPERX', 'WhisperX · Local', NULL, true),
        (gen_random_uuid(), 'LOCAL_KOKORO', 'Kokoro · Local', NULL, true),
        (gen_random_uuid(), 'LOCAL_NLP', 'NLP · Local', NULL, true),
        (gen_random_uuid(), 'DEEPSEEK', 'DeepSeek', 'https://api.deepseek.com', false),
        (gen_random_uuid(), 'GEMINI', 'Gemini', 'https://generativelanguage.googleapis.com/v1beta', false),
        (gen_random_uuid(), 'GROQ', 'Groq', 'https://api.groq.com/openai/v1', false)
    ON CONFLICT (code) DO NOTHING;

INSERT INTO ai_capability_route (id, provider_id, capability, model, priority)
SELECT gen_random_uuid(), provider.id, route.capability, route.model, route.priority
FROM ai_provider provider
JOIN (VALUES
    ('LOCAL_WHISPERX', 'STT', 'small.en', 1),
    ('LOCAL_WHISPERX', 'ALIGNMENT', 'WAV2VEC2_ASR_BASE_960H', 1),
    ('LOCAL_KOKORO', 'TTS', 'hexgrad/Kokoro-82M', 1),
    ('LOCAL_NLP', 'NLP', 'espeak-ng', 1),
    ('DEEPSEEK', 'GENERAL_LLM', 'deepseek-flash', 1),
    ('GEMINI', 'GENERAL_LLM', 'gemini-3.5-flash-lite', 2),
    ('GROQ', 'GENERAL_LLM', 'openai/gpt-oss-120b', 3)
) route (provider_code, capability, model, priority) ON route.provider_code=provider.code
ON CONFLICT (capability, provider_id, model) DO NOTHING;
COMMIT;
