CREATE TABLE dataset_import (
    id UUID PRIMARY KEY,
    dataset_code VARCHAR(100) NOT NULL,
    dataset_version VARCHAR(100),
    source_path TEXT,
    checksum VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK(status IN ('PLANNED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    records_imported BIGINT NOT NULL DEFAULT 0,
    media_imported BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(dataset_code, checksum)
);

-- Small stable reference seed belongs in Flyway. Large datasets do not.
INSERT INTO grammar_difficulty_level(level, name_vi, description_vi) VALUES
    (1, 'Rất dễ', 'Mức nền tảng'),
    (2, 'Dễ', 'Câu hỏi cơ bản'),
    (3, 'Trung bình', 'Mức trung bình'),
    (4, 'Khó', 'Yêu cầu phân biệt cấu trúc tốt'),
    (5, 'Rất khó', 'Mức nâng cao')
ON CONFLICT (level) DO NOTHING;

-- Small product defaults. Admin can disable/edit missions later; learner progress is never seeded here.
INSERT INTO mission_definition(id,code,title,metric,target,diamond_reward,recurrence,enabled) VALUES
    ('10000000-0000-0000-0000-000000000001','DAILY_LESSON','Hoàn thành 1 bài học','LESSON_COMPLETED',1,3,'DAILY',true),
    ('10000000-0000-0000-0000-000000000002','DAILY_VOCAB_10','Ôn 10 từ vựng','VOCABULARY_REVIEW',10,2,'DAILY',true),
    ('10000000-0000-0000-0000-000000000003','DAILY_TOEIC','Hoàn thành 1 TOEIC drill','TOEIC_DRILL_COMPLETED',1,4,'DAILY',true)
ON CONFLICT (code) DO NOTHING;
