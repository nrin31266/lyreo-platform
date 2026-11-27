ALTER TABLE learner_profile ADD COLUMN toeic_target_score smallint
    CHECK (toeic_target_score BETWEEN 10 AND 990 AND toeic_target_score % 5 = 0);
