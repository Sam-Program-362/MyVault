-- These triggers preserve legacy duplicates while rejecting newly introduced
-- scoped-name collisions. One transaction-wide lock serializes checks, including
-- attachment and scope changes, so concurrent writes cannot bypass them.
CREATE FUNCTION myvault_guard_tag() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(1836674164);
  IF NEW.name IS NULL OR btrim(NEW.name) = '' OR
     NOT ((NEW.scope = 'global' AND NEW.owner_space_id IS NULL) OR
          (NEW.scope = 'local' AND NEW.owner_space_id IS NOT NULL)) THEN
    RAISE EXCEPTION 'Invalid tag name or scope ownership' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'INSERT' OR lower(NEW.name) IS DISTINCT FROM lower(OLD.name)
     OR NEW.scope IS DISTINCT FROM OLD.scope OR NEW.owner_space_id IS DISTINCT FROM OLD.owner_space_id THEN
    IF EXISTS (SELECT 1 FROM tags t WHERE t.id <> NEW.id AND lower(t.name) = lower(NEW.name)
      AND t.scope = NEW.scope AND t.owner_space_id IS NOT DISTINCT FROM NEW.owner_space_id) THEN
      RAISE EXCEPTION 'Tag name already exists in this scope' USING ERRCODE = '23505';
    END IF;
  END IF;
  IF NEW.scope = 'local' AND EXISTS (
      SELECT 1 FROM entry_tags et JOIN entries e ON e.id = et.entry_id
      WHERE et.tag_id = NEW.id AND e.space_id <> NEW.owner_space_id) THEN
    RAISE EXCEPTION 'Tag is attached to entries in another Space' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER myvault_tag_guard BEFORE INSERT OR UPDATE ON tags FOR EACH ROW EXECUTE FUNCTION myvault_guard_tag();

CREATE FUNCTION myvault_guard_tag_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(1836674164);
  IF EXISTS (SELECT 1 FROM tags t JOIN entries e ON e.id = NEW.entry_id
    WHERE t.id = NEW.tag_id AND t.scope = 'local' AND t.owner_space_id <> e.space_id) THEN
    RAISE EXCEPTION 'Tag belongs to another Space' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER myvault_tag_link_guard BEFORE INSERT OR UPDATE ON entry_tags FOR EACH ROW EXECUTE FUNCTION myvault_guard_tag_link();

CREATE FUNCTION myvault_guard_entry_space() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(1836674164);
  IF EXISTS (SELECT 1 FROM entry_tags et JOIN tags t ON t.id = et.tag_id
    WHERE et.entry_id = NEW.id AND t.scope = 'local' AND t.owner_space_id <> NEW.space_id) THEN
    RAISE EXCEPTION 'Entry has a local tag from another Space' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER myvault_entry_space_guard BEFORE UPDATE OF space_id ON entries FOR EACH ROW EXECUTE FUNCTION myvault_guard_entry_space();
