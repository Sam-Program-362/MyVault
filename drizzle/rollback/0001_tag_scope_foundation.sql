-- Run only after removing the 0003 guards and only if no Local tags exist.
-- This restores the pre-scope columns without touching tag IDs, names, reusable,
-- or entry_tags. Refuse rollback if it would discard scoped metadata.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM tags WHERE scope <> 'global' OR owner_space_id IS NOT NULL) THEN
    RAISE EXCEPTION 'Cannot safely remove tag scope columns while Local/scoped tags exist';
  END IF;
END $$;
ALTER TABLE tags DROP COLUMN owner_space_id;
ALTER TABLE tags DROP COLUMN scope;
