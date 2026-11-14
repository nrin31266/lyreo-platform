ALTER TABLE lesson_youtube_channel ADD COLUMN visible BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE lesson_audio_source (
    id UUID PRIMARY KEY,
    object_key TEXT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0 AND duration_ms <= 7200000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE lesson_authoring ALTER COLUMN video_id DROP NOT NULL;
ALTER TABLE lesson_authoring ALTER COLUMN source_end_ms DROP NOT NULL;
ALTER TABLE lesson_authoring ADD COLUMN audio_source_id UUID REFERENCES lesson_audio_source(id);
ALTER TABLE lesson_authoring ADD COLUMN tts_voice VARCHAR(100);
ALTER TABLE lesson_authoring_job DROP CONSTRAINT lesson_authoring_job_operation_check;
ALTER TABLE lesson_authoring_job ADD CONSTRAINT lesson_authoring_job_operation_check
    CHECK (operation IN ('PREPARE','ALIGN','SEMANTIC','IPA','TTS'));
CREATE VIEW lesson_availability AS
SELECT l.id AS lesson_id,
    l.status='PUBLISHED' AND m.deleted_at IS NULL AND m.visible AND COALESCE(c.visible,TRUE) AS available,
    COALESCE(c.visible,TRUE) AS channel_visible
FROM lesson l JOIN lesson_metadata m ON m.lesson_id=l.id
LEFT JOIN lesson_authoring a ON a.lesson_id=l.id
LEFT JOIN lesson_youtube_source s ON s.video_id=a.video_id
LEFT JOIN lesson_youtube_channel c ON c.channel_id=s.channel_id;
