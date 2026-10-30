// Standalone research prototype. Never connect this to an application database.
// Node >=22.13: npm install --no-save --package-lock=false @electric-sql/pglite@0.5.13
// node --experimental-sqlite experiments/tag-name/sql-proof.mjs
import { DatabaseSync } from 'node:sqlite';
import { PGlite } from '@electric-sql/pglite';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const fixture = JSON.parse(readFileSync(new URL('../../tests/fixtures/tag-name-policy-vectors.json', import.meta.url)));
const data = JSON.parse(readFileSync(new URL('./ucd-15.1.json', import.meta.url)));
assert.equal(data.unicode, '15.1.0');
const whitespace = [9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288].join(',');

// NFD comparison keys are canonically equivalent to the policy's final NFC
// keys; they are used only for equality in this experiment. The first ordering
// pass MUST precede folding (e.g. Greek U+0345). No engine Unicode routines.
function viewSql(engine) {
 const ord = engine === 'sqlite' ? 'unicode' : 'ascii';
 const collation = engine === 'sqlite' ? 'BINARY' : '"C"';
 const agg = engine === 'sqlite' ? "group_concat(c,'')" : "string_agg(c,'' ORDER BY bucket,cc,p)";
 const sorted = (chars, raw) => engine === 'sqlite'
   ? `(SELECT coalesce(group_concat(c,''),'') FROM (SELECT p.c FROM ${chars} p WHERE p.id = ${raw}.id ORDER BY (SELECT count(*) FROM ${chars} z WHERE z.id=p.id AND z.p<=p.p AND z.cc=0),p.cc,p.p))`
   : `(SELECT coalesce(${agg},'') FROM (SELECT p.c,p.cc,p.p,(SELECT count(*) FROM ${chars} z WHERE z.id=p.id AND z.p<=p.p AND z.cc=0) AS bucket FROM ${chars} p WHERE p.id=${raw}.id) AS q)`;
 const ch = (expr, mapping) => `coalesce((SELECT ${mapping} FROM ucd WHERE cp=${ord}(${expr})),${expr})`;
 const cc = expr => `coalesce((SELECT cc FROM ucd WHERE cp=${ord}(${expr})),0)`;
 const c0 = 'substr(r0.s,1,1)', c0next = 'substr(r0.s,c0.p+1,1)';
 const c1 = 'substr(r1.s,1,1)', c1next = 'substr(r1.s,c1.p+1,1)';
 const orig = 'substr(n.name,d0.i,1)', folded = 'substr(o0.s,d1.i,1)';
 return `CREATE VIEW keys AS WITH RECURSIVE
 d0(id,i,s) AS (SELECT id,1,CAST('' AS TEXT) FROM n UNION ALL SELECT d0.id,d0.i+1,d0.s || CASE WHEN ${ord}(${orig}) IN (${whitespace}) THEN ' ' ELSE ${ch(orig,'nfd')} END FROM d0 JOIN n ON n.id=d0.id WHERE d0.i<=length(n.name)),
 r0(id,s) AS (SELECT d0.id,d0.s FROM d0 JOIN n ON n.id=d0.id WHERE d0.i>length(n.name)),
 c0(id,p,c,cc) AS (SELECT id,1,${c0},${cc(c0)} FROM r0 WHERE length(s)>0 UNION ALL SELECT c0.id,c0.p+1,${c0next},${cc(c0next)} FROM c0 JOIN r0 ON r0.id=c0.id WHERE c0.p<length(r0.s)),
 o0(id,s) AS (SELECT r0.id,${sorted('c0','r0')} FROM r0),
 d1(id,i,s) AS (SELECT id,1,CAST('' AS TEXT) FROM o0 UNION ALL SELECT d1.id,d1.i+1,d1.s || ${ch(folded,'fold')} FROM d1 JOIN o0 ON o0.id=d1.id WHERE d1.i<=length(o0.s)),
 r1(id,s) AS (SELECT d1.id,d1.s FROM d1 JOIN o0 ON o0.id=d1.id WHERE d1.i>length(o0.s)),
 c1(id,p,c,cc) AS (SELECT id,1,${c1},${cc(c1)} FROM r1 WHERE length(s)>0 UNION ALL SELECT c1.id,c1.p+1,${c1next},${cc(c1next)} FROM c1 JOIN r1 ON r1.id=c1.id WHERE c1.p<length(r1.s)),
 o1(id,s) AS (SELECT r1.id,${sorted('c1','r1')} FROM r1),
 spaces(id,i,s) AS (SELECT id,1,CAST('' AS TEXT) FROM o1 UNION ALL SELECT spaces.id,spaces.i+1,CASE WHEN substr(o1.s,spaces.i,1)=' ' AND (length(spaces.s)=0 OR substr(spaces.s,length(spaces.s),1)=' ') THEN spaces.s ELSE spaces.s || substr(o1.s,spaces.i,1) END FROM spaces JOIN o1 ON o1.id=spaces.id WHERE spaces.i<=length(o1.s))
 SELECT spaces.id,rtrim(spaces.s,' ') COLLATE ${collation} AS identity FROM spaces JOIN o1 ON o1.id=spaces.id WHERE spaces.i>length(o1.s)`;
}
const sqlite = new DatabaseSync(':memory:');
const pg = await PGlite.create(); // embedded, disposable PostgreSQL; never a remote service
sqlite.exec("CREATE TABLE ucd(cp INTEGER PRIMARY KEY,nfd TEXT,fold TEXT,cc INTEGER NOT NULL); CREATE TABLE n(id INTEGER PRIMARY KEY,name TEXT NOT NULL,scope TEXT NOT NULL DEFAULT 'global',owner TEXT,reusable INTEGER NOT NULL DEFAULT 1)");
await pg.exec("CREATE TABLE ucd(cp INTEGER PRIMARY KEY,nfd TEXT,fold TEXT,cc INTEGER NOT NULL); CREATE TABLE n(id INTEGER PRIMARY KEY,name TEXT NOT NULL,scope TEXT NOT NULL DEFAULT 'global',owner TEXT,reusable INTEGER NOT NULL DEFAULT 1)");
sqlite.exec('BEGIN'); const ins = sqlite.prepare('INSERT INTO ucd VALUES (?,?,?,?)');
for (const row of data.rows) ins.run(...row);
sqlite.exec('COMMIT');
await pg.query('INSERT INTO ucd SELECT cp,nfd,fold,cc FROM json_to_recordset($1::json) AS x(cp integer,nfd text,fold text,cc integer)', [JSON.stringify(data.rows.map(([cp,nfd,fold,cc]) => ({cp,nfd,fold,cc})))]);
sqlite.exec(viewSql('sqlite')); await pg.exec(viewSql('pg'));
const inputs = [];
for (const pair of fixture.pairs) inputs.push(pair.left,pair.right);
for (const test of fixture.scopedCases) inputs.push(...test.tags.map(t=>t.name));
inputs.push('', ' \u00a0\t', 'Café');
const sqliteIns = sqlite.prepare('INSERT INTO n(id,name) VALUES (?,?)');
sqlite.exec('BEGIN'); inputs.forEach((v,i)=>sqliteIns.run(i,v)); sqlite.exec('COMMIT');
await pg.query('INSERT INTO n(id,name) SELECT id,name FROM json_to_recordset($1::json) AS x(id integer,name text)', [JSON.stringify(inputs.map((name,id)=>({id,name})))]);
const sqliteKeys=sqlite.prepare('SELECT id,identity FROM keys ORDER BY id').all().map(r=>r.identity);
const pgKeys=(await pg.query('SELECT id,identity FROM keys ORDER BY id')).rows.map(r=>r.identity);
assert.deepEqual(sqliteKeys,pgKeys,'different DB identity keys');
let i=0;
for (const pair of fixture.pairs) {assert.equal(sqliteKeys[i]===sqliteKeys[i+1],pair.equal,JSON.stringify(pair)); i+=2;}
for (const test of fixture.scopedCases) {
 const [a,b]=test.tags;
 assert.equal(sqliteKeys[i]===sqliteKeys[i+1] && a.scope===b.scope && a.owner===b.owner,test.conflict,JSON.stringify(test)); i+=2;
}
assert.equal(sqliteKeys[i],''); assert.equal(sqliteKeys[i+1],'');
console.log(`PASS SQLite and PostgreSQL: ${fixture.pairs.length} comparison vectors, ${fixture.scopedCases.length} scope cases, 2 empty-name keys; ${inputs.length} identical NFD keys.`);
// Install prototype guards *after* deliberately creating legacy collisions.
// This is a disposable test schema, not a migration or application code.
sqlite.exec("DELETE FROM n; INSERT INTO n(id,name) VALUES(1,'Café'),(2,'Café'); CREATE TABLE links(entry_id INTEGER,tag_id INTEGER); INSERT INTO links VALUES(11,1),(12,2)");
await pg.exec("DELETE FROM n; INSERT INTO n(id,name) VALUES(1,'Café'),(2,'Café'); CREATE TABLE links(entry_id INTEGER,tag_id INTEGER); INSERT INTO links VALUES(11,1),(12,2)");
const sqliteCheck = `SELECT CASE WHEN (SELECT identity FROM keys WHERE id=NEW.id) = '' THEN RAISE(ABORT,'empty') END;
 SELECT CASE WHEN EXISTS (SELECT 1 FROM keys a JOIN keys b ON a.identity=b.identity JOIN n x ON x.id=a.id JOIN n y ON y.id=b.id WHERE a.id=NEW.id AND b.id<>NEW.id AND x.scope=y.scope AND x.owner IS y.owner) THEN RAISE(ABORT,'collision') END;`;
sqlite.exec(`CREATE TRIGGER proof_ins AFTER INSERT ON n BEGIN ${sqliteCheck} END;
CREATE TRIGGER proof_upd AFTER UPDATE OF name,scope,owner ON n WHEN OLD.name<>NEW.name OR OLD.scope<>NEW.scope OR OLD.owner IS NOT NEW.owner BEGIN ${sqliteCheck} END;`);
await pg.exec(`CREATE FUNCTION proof_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 PERFORM pg_advisory_xact_lock(1836674164);
 IF TG_OP = 'UPDATE' AND OLD.name=NEW.name AND OLD.scope=NEW.scope AND OLD.owner IS NOT DISTINCT FROM NEW.owner THEN RETURN NEW; END IF;
 IF (SELECT identity FROM keys WHERE id=NEW.id)='' THEN RAISE EXCEPTION 'empty'; END IF;
 IF EXISTS (SELECT 1 FROM keys a JOIN keys b ON a.identity=b.identity JOIN n x ON x.id=a.id JOIN n y ON y.id=b.id WHERE a.id=NEW.id AND b.id<>NEW.id AND x.scope=y.scope AND x.owner IS NOT DISTINCT FROM y.owner) THEN RAISE EXCEPTION 'collision'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER proof_guard AFTER INSERT OR UPDATE ON n FOR EACH ROW EXECUTE FUNCTION proof_guard();`);
// Both triggers reject conflicts and empty keys, while leaving legacy data intact.
assert.throws(()=>sqlite.exec("INSERT INTO n(id,name) VALUES(3,'CAFÉ')"),/collision/);
await assert.rejects(pg.exec("INSERT INTO n(id,name) VALUES(3,'CAFÉ')"),/collision/);
assert.throws(()=>sqlite.exec("INSERT INTO n(id,name) VALUES(4,'   ' )"),/empty/);
await assert.rejects(pg.exec("INSERT INTO n(id,name) VALUES(4,'   ' )"),/empty/);
for (const sql of ["UPDATE n SET reusable=0 WHERE id=1", "INSERT INTO n(id,name,scope,owner,reusable) VALUES(5,'CAFÉ','local','space-a',0)", "INSERT INTO n(id,name,scope,owner,reusable) VALUES(6,'CAFÉ','local','space-b',1)"]) {sqlite.exec(sql); await pg.exec(sql);}
assert.throws(()=>sqlite.exec("INSERT INTO n(id,name,scope,owner,reusable) VALUES(7,'CAFÉ','local','space-a',1)"),/collision/);
await assert.rejects(pg.exec("INSERT INTO n(id,name,scope,owner,reusable) VALUES(7,'CAFÉ','local','space-a',1)"),/collision/);
// Document a known limitation, not a passing production-compatibility test.
assert.throws(()=>sqlite.exec("UPDATE n SET name='café' WHERE id=1"),/collision/);
await assert.rejects(pg.exec("UPDATE n SET name='café' WHERE id=1"),/collision/);
// A failed write followed by an explicit rollback leaves no partial record.
sqlite.exec('BEGIN'); sqlite.exec("INSERT INTO n(id,name) VALUES(8,'temporary')");
assert.throws(()=>sqlite.exec("INSERT INTO n(id,name) VALUES(9,'café')"),/collision/); sqlite.exec('ROLLBACK');
await pg.exec('BEGIN'); await pg.exec("INSERT INTO n(id,name) VALUES(8,'temporary')");
await assert.rejects(pg.exec("INSERT INTO n(id,name) VALUES(9,'café')"),/collision/); await pg.exec('ROLLBACK');
assert.equal(sqlite.prepare('SELECT count(*) AS c FROM links').get().c,2);
assert.equal((await pg.query('SELECT count(*)::int AS c FROM links')).rows[0].c,2);
assert.equal(sqlite.prepare('SELECT count(*) AS c FROM n').get().c,4);
assert.equal((await pg.query('SELECT count(*)::int AS c FROM n')).rows[0].c,4);
console.log('PASS SQLite and PostgreSQL prototype triggers: legacy IDs/links retained, unchanged reusable update, conflicts and empty keys rejected, scope separation and rollback.');
console.log('LIMITATIONS: change to canonically equivalent spelling of a legacy duplicate is rejected; no concurrent writers, Room migration, Android driver, or network PostgreSQL tested.');
sqlite.close(); await pg.close();
