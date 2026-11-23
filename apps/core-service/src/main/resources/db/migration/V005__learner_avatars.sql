ALTER TABLE learner_profile
    ADD COLUMN avatar_preset text CHECK (avatar_preset IN ('CAT', 'DOG', 'FOX', 'RABBIT', 'PANDA', 'BEAR')),
    ADD COLUMN avatar_upload_id uuid,
    ADD COLUMN avatar_png bytea,
    ADD CONSTRAINT learner_avatar_consistent CHECK (
        (avatar_upload_id IS NULL AND avatar_png IS NULL)
        OR (avatar_upload_id IS NOT NULL AND avatar_preset IS NULL
            AND avatar_png IS NOT NULL AND octet_length(avatar_png) BETWEEN 1 AND 270000)
    );
