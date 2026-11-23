ALTER TABLE learner_profile
    DROP CONSTRAINT learner_profile_avatar_preset_check,
    ADD CONSTRAINT learner_profile_avatar_preset_check CHECK (avatar_preset ~ '^[A-Z][A-Z0-9_]{0,39}$');
