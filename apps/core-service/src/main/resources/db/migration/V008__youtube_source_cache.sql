CREATE TABLE lesson_youtube_source (
    video_id VARCHAR(11) PRIMARY KEY,
    title TEXT NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    embed_allowed BOOLEAN NOT NULL,
    audio_object_key TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE lesson_youtube_segment (
    video_id VARCHAR(11) NOT NULL REFERENCES lesson_youtube_source(video_id),
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    end_ms BIGINT NOT NULL CHECK (end_ms > start_ms),
    normalization_version VARCHAR(32) NOT NULL,
    audio_object_key TEXT NOT NULL,
    PRIMARY KEY (video_id, start_ms, end_ms, normalization_version)
);
