-- Fail closed until both Android SQLite and PostgreSQL can enforce the pinned
-- Unicode 15.1 name identity. Existing rows are never normalized or rewritten.
-- Metadata-only edits (color, text color, reusable) remain available.
CREATE OR REPLACE FUNCTION myvault_guard_tag() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    RAISE EXCEPTION 'Tag creation is paused until Unicode 15.1 uniqueness is enforced on every platform'
      USING ERRCODE = '55000';
  END IF;
  IF NEW.name IS DISTINCT FROM OLD.name OR NEW.scope IS DISTINCT FROM OLD.scope
     OR NEW.owner_space_id IS DISTINCT FROM OLD.owner_space_id THEN
    RAISE EXCEPTION 'Tag name and scope changes are paused until Unicode 15.1 uniqueness is enforced on every platform'
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS myvault_tag_guard ON tags;
CREATE TRIGGER myvault_tag_guard BEFORE INSERT OR UPDATE ON tags
  FOR EACH ROW EXECUTE FUNCTION myvault_guard_tag();

-- Keep Local ownership valid even for direct SQL and future import paths.
CREATE OR REPLACE FUNCTION myvault_guard_tag_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(1836674164);
  IF EXISTS (
    SELECT 1 FROM tags t JOIN entries e ON e.id = NEW.entry_id
    WHERE t.id = NEW.tag_id AND t.scope = 'local'
      AND t.owner_space_id IS DISTINCT FROM e.space_id
  ) THEN
    RAISE EXCEPTION 'Local tag belongs to another Space' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS myvault_tag_link_guard ON entry_tags;
CREATE TRIGGER myvault_tag_link_guard BEFORE INSERT OR UPDATE ON entry_tags
  FOR EACH ROW EXECUTE FUNCTION myvault_guard_tag_link();

CREATE OR REPLACE FUNCTION myvault_guard_entry_space() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(1836674164);
  IF EXISTS (
    SELECT 1 FROM entry_tags et JOIN tags t ON t.id = et.tag_id
    WHERE et.entry_id = NEW.id AND t.scope = 'local'
      AND t.owner_space_id IS DISTINCT FROM NEW.space_id
  ) THEN
    RAISE EXCEPTION 'Entry has a Local tag from another Space' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS myvault_entry_space_guard ON entries;
CREATE TRIGGER myvault_entry_space_guard BEFORE UPDATE OF space_id ON entries
  FOR EACH ROW EXECUTE FUNCTION myvault_guard_entry_space();
