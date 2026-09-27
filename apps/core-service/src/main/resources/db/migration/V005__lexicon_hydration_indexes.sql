-- Hydrate active Lexicon entries without scanning every child row in a release.
CREATE INDEX lexicon_sense_entry_idx ON lexicon_sense(release_id, entry_id);
CREATE INDEX lexicon_form_entry_idx ON lexicon_form(release_id, entry_id);
CREATE INDEX lexicon_pronunciation_entry_idx ON lexicon_pronunciation(release_id, entry_id);
