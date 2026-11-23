ALTER TABLE learner_profile
    DROP CONSTRAINT learner_profile_avatar_preset_check,
    ADD CONSTRAINT learner_profile_avatar_preset_check CHECK (
        avatar_preset IN ('CAT', 'DOG', 'FOX', 'RABBIT', 'PANDA', 'BEAR',
                          'RED_PANDA', 'KOALA', 'LION', 'PENGUIN')
    );
