ALTER TABLE lesson_youtube_source
    ADD COLUMN english_caption_language text,
    ADD COLUMN english_caption_automatic boolean NOT NULL DEFAULT false,
    ADD COLUMN english_captions_checked_at timestamptz;
