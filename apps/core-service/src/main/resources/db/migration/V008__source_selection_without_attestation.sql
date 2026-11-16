-- Selecting a source no longer records a rights attestation.
ALTER TABLE lesson_authoring RENAME COLUMN rights_confirmed_at TO source_selected_at;
