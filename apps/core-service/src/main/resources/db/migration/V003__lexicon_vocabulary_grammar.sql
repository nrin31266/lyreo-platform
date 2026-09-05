CREATE TABLE lexicon_entry (
    id UUID PRIMARY KEY,
    canonical_form TEXT NOT NULL,
    normalized_form TEXT NOT NULL,
    entry_type VARCHAR(32) NOT NULL
        CHECK(entry_type IN ('WORD','PHRASE','PHRASAL_VERB','IDIOM','COLLOCATION')),
    language VARCHAR(16) NOT NULL DEFAULT 'en',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    relevance_score NUMERIC(10,4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    UNIQUE(language, normalized_form, entry_type)
);
CREATE INDEX lexicon_entry_form_idx ON lexicon_entry(normalized_form);

CREATE TABLE lexicon_form (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL REFERENCES lexicon_entry(id) ON DELETE CASCADE,
    form TEXT NOT NULL,
    normalized_form TEXT NOT NULL,
    form_type VARCHAR(32),
    UNIQUE(entry_id, normalized_form)
);
CREATE INDEX lexicon_form_lookup_idx ON lexicon_form(normalized_form);

CREATE TABLE lexicon_sense (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL REFERENCES lexicon_entry(id) ON DELETE CASCADE,
    position INT NOT NULL,
    part_of_speech VARCHAR(32),
    definition_en TEXT,
    translation_vi TEXT,
    translation_status VARCHAR(32) NOT NULL DEFAULT 'MISSING'
        CHECK(translation_status IN ('AVAILABLE','MISSING','NEEDS_REVIEW','AI_GENERATED','VERIFIED')),
    source_id UUID REFERENCES data_source(id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(entry_id, position)
);

CREATE TABLE lexicon_pronunciation (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL REFERENCES lexicon_entry(id) ON DELETE CASCADE,
    accent VARCHAR(32),
    ipa TEXT,
    external_audio_url TEXT,
    cached_audio_object_key TEXT,
    source_id UUID REFERENCES data_source(id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE vocabulary_card (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    lexicon_entry_id UUID NOT NULL REFERENCES lexicon_entry(id),
    source_context_type VARCHAR(64),
    source_context_id UUID,
    next_review_at TIMESTAMPTZ NOT NULL,
    stability NUMERIC(12,4) NOT NULL DEFAULT 1,
    difficulty NUMERIC(12,4) NOT NULL DEFAULT 5,
    lapse_count INT NOT NULL DEFAULT 0,
    review_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    suspended_at TIMESTAMPTZ,
    UNIQUE(learner_id, lexicon_entry_id)
);
CREATE INDEX vocabulary_due_idx ON vocabulary_card(learner_id, next_review_at);

CREATE TABLE vocabulary_review (
    id UUID PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES vocabulary_card(id) ON DELETE CASCADE,
    rating VARCHAR(16) NOT NULL CHECK(rating IN ('AGAIN','HARD','GOOD','EASY')),
    previous_due_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ NOT NULL,
    stability NUMERIC(12,4),
    difficulty NUMERIC(12,4),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE grammar_topic (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    code VARCHAR(128),
    name_en TEXT,
    name_vi TEXT NOT NULL,
    description_vi TEXT,
    position INT
);

CREATE TABLE grammar_subtopic (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    topic_id UUID REFERENCES grammar_topic(id),
    code VARCHAR(128),
    name_en TEXT,
    name_vi TEXT NOT NULL,
    description_vi TEXT,
    position INT
);

CREATE TABLE grammar_difficulty_level (
    level INT PRIMARY KEY,
    name_vi TEXT NOT NULL,
    description_vi TEXT
);

CREATE TABLE grammar_bank_set (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    name TEXT NOT NULL,
    source_url TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE grammar_question (
    id UUID PRIMARY KEY,
    external_question_id TEXT UNIQUE,
    question_text TEXT NOT NULL,
    option_a TEXT,
    option_b TEXT,
    option_c TEXT,
    option_d TEXT,
    correct_answer VARCHAR(8) NOT NULL,
    explanation_en TEXT,
    explanation_vi TEXT,
    translation_vi TEXT,
    answer_translation_vi TEXT,
    vocabulary_note TEXT,
    difficulty_level INT REFERENCES grammar_difficulty_level(level),
    topic_id UUID REFERENCES grammar_topic(id),
    subtopic_id UUID REFERENCES grammar_subtopic(id),
    explanation_policy VARCHAR(32) NOT NULL DEFAULT 'SOURCE',
    source_test_id TEXT,
    source_test_name TEXT,
    source_question_number INT,
    source_url TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE grammar_question_membership (
    question_id UUID NOT NULL REFERENCES grammar_question(id) ON DELETE CASCADE,
    bank_set_id UUID NOT NULL REFERENCES grammar_bank_set(id) ON DELETE CASCADE,
    position INT,
    PRIMARY KEY(question_id, bank_set_id)
);

CREATE TABLE grammar_practice_attempt (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES grammar_question(id),
    answer VARCHAR(8),
    correct BOOLEAN NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX grammar_attempt_learner_idx
    ON grammar_practice_attempt(learner_id, answered_at DESC);
