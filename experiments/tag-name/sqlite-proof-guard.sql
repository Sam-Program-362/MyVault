-- Disposable proof guard for SQLite. Seed identity_cache from existing rows
-- before installing triggers; duplicates are intentionally retained there.
CREATE TABLE identity_cache(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,owner TEXT,identity TEXT NOT NULL);
CREATE INDEX identity_cache_lookup ON identity_cache(scope,owner,identity);
CREATE TABLE pending_identity(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,owner TEXT,identity TEXT NOT NULL);
INSERT INTO identity_cache(id,scope,owner,identity)
SELECT n.id,n.scope,n.owner,(SELECT keys.identity FROM keys WHERE keys.id=n.id) FROM n;

CREATE TRIGGER proof_ins AFTER INSERT ON n BEGIN
  SELECT CASE WHEN instr(NEW.name,char(0))>0 THEN RAISE(ABORT,'embedded NUL is not supported') END;
  SELECT CASE WHEN length(NEW.name)>24 THEN RAISE(ABORT,'maximum tag name length is 24 code points') END;
  INSERT INTO pending_identity(id,scope,owner,identity)
    SELECT NEW.id,NEW.scope,NEW.owner,identity FROM keys WHERE id=NEW.id;
  SELECT CASE WHEN (SELECT identity FROM pending_identity WHERE id=NEW.id)=''
    THEN RAISE(ABORT,'empty') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM identity_cache c JOIN pending_identity p
      ON c.scope=p.scope AND c.owner IS p.owner AND c.identity=p.identity
    WHERE c.id<>p.id
  ) THEN RAISE(ABORT,'collision') END;
  INSERT INTO identity_cache SELECT id,scope,owner,identity FROM pending_identity WHERE id=NEW.id;
  DELETE FROM pending_identity WHERE id=NEW.id;
END;

CREATE TRIGGER proof_upd AFTER UPDATE OF name,scope,owner ON n
WHEN OLD.name IS NOT NEW.name OR OLD.scope IS NOT NEW.scope OR OLD.owner IS NOT NEW.owner
BEGIN
  SELECT CASE WHEN instr(NEW.name,char(0))>0 THEN RAISE(ABORT,'embedded NUL is not supported') END;
  SELECT CASE WHEN length(NEW.name)>24 THEN RAISE(ABORT,'maximum tag name length is 24 code points') END;
  INSERT INTO pending_identity(id,scope,owner,identity)
    SELECT NEW.id,NEW.scope,NEW.owner,identity FROM keys WHERE id=NEW.id;
  SELECT CASE WHEN (SELECT identity FROM pending_identity WHERE id=NEW.id)=''
    THEN RAISE(ABORT,'empty') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM identity_cache c JOIN pending_identity p
      ON c.scope=p.scope AND c.owner IS p.owner AND c.identity=p.identity
    WHERE c.id<>p.id
  ) AND NOT EXISTS (
    SELECT 1 FROM identity_cache old JOIN pending_identity p ON old.id=p.id
    WHERE old.scope IS p.scope AND old.owner IS p.owner AND old.identity=p.identity
  ) THEN RAISE(ABORT,'collision') END;
  DELETE FROM identity_cache WHERE id=NEW.id;
  INSERT INTO identity_cache SELECT id,scope,owner,identity FROM pending_identity WHERE id=NEW.id;
  DELETE FROM pending_identity WHERE id=NEW.id;
END;
