CREATE INDEX lexicon_entry_admin_cursor_idx ON lexicon_entry (release_id, id);
CREATE INDEX lexicon_item_admin_pos_idx ON lexicon_item (release_id, part_of_speech, entry_id);
CREATE INDEX lexicon_sense_admin_translation_idx ON lexicon_sense (release_id, translation_status, entry_id);
