ALTER TABLE dataset_release DROP CONSTRAINT dataset_release_domain_check;
ALTER TABLE dataset_release ADD CONSTRAINT dataset_release_domain_check
    CHECK (domain IN ('lexicon', 'grammar-toeic', 'vocabulary'));

CREATE TABLE vocabulary_pack (
    id uuid PRIMARY KEY,
    release_id uuid NOT NULL REFERENCES dataset_release(id),
    source_topic text NOT NULL,
    position integer NOT NULL CHECK (position > 0),
    title_en text NOT NULL CHECK (btrim(title_en) <> ''),
    title_vi text NOT NULL CHECK (btrim(title_vi) <> ''),
    hidden boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0,
    UNIQUE (release_id, source_topic),
    UNIQUE (release_id, position)
);

CREATE TABLE vocabulary_unit (
    id uuid PRIMARY KEY,
    pack_id uuid NOT NULL REFERENCES vocabulary_pack(id),
    position integer NOT NULL CHECK (position > 0),
    level text NOT NULL CHECK (level IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
    title text NOT NULL CHECK (btrim(title) <> ''),
    hidden boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0,
    UNIQUE (pack_id, position)
);

CREATE TABLE vocabulary_unit_entry (
    id uuid PRIMARY KEY,
    unit_id uuid NOT NULL REFERENCES vocabulary_unit(id),
    position integer NOT NULL CHECK (position > 0),
    source_id text NOT NULL UNIQUE,
    term text NOT NULL CHECK (btrim(term) <> ''),
    content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    image_id uuid REFERENCES content_asset(id),
    uk_audio_id uuid REFERENCES content_asset(id),
    us_audio_id uuid REFERENCES content_asset(id),
    example_audio_id uuid REFERENCES content_asset(id),
    second_example_audio_id uuid REFERENCES content_asset(id),
    hidden boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0,
    UNIQUE (unit_id, position)
);
