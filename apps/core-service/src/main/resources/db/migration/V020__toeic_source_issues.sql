ALTER TABLE toeic_stimulus_group ADD COLUMN source_issues jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE toeic_stimulus_group ADD CONSTRAINT toeic_source_issues_array CHECK (jsonb_typeof(source_issues)='array');
