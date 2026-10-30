// Standalone research prototype. Never connect this to an application database.
// Node >=22.13: npm install --no-save --package-lock=false @electric-sql/pglite@0.5.13
// node --experimental-sqlite experiments/tag-name/sql-proof.mjs
import { DatabaseSync } from 'node:sqlite';
import { PGlite } from '@electric-sql/pglite';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Worker } from 'node:worker_threads';
import { randomBytes } from 'node:crypto';
import assert from 'node:assert/strict';

const fixture = JSON.parse(readFileSync(new URL('../../tests/fixtures/tag-name-policy-vectors.json', import.meta.url)));
assert.equal(readFileSync(new URL('./sqlite-proof-view.sql',import.meta.url),'utf8'),viewSql('sqlite')+';\n','Android SQLite view is out of date');
const data = JSON.parse(readFileSync(new URL('./ucd-15.1.json', import.meta.url)));
assert.equal(data.unicode, '15.1.0');
import { viewSql } from './sql-template.mjs';
const tempDir=mkdtempSync(join(tmpdir(),'myvault-sql-proof-'));
const sqliteFile=join(tempDir,'proof.sqlite');
const sqlite = new DatabaseSync(sqliteFile);
// Optional real PostgreSQL is strictly restricted to an explicitly authorized,
// localhost-only test database. Otherwise use disposable embedded PGlite.
const pgUrl=process.env.MYVAULT_PROOF_PG_URL;
let pg, pgClient, pgSchema;
if (pgUrl) {
 const url=new URL(pgUrl);
 if (process.env.MYVAULT_DISPOSABLE_DATABASE !== '1' || url.hostname !== '127.0.0.1' || url.pathname !== '/tag_identity_proof') throw new Error('Only explicitly authorized localhost tag_identity_proof database is allowed');
 const {Client}=await import('pg'); pgClient=new Client({connectionString:pgUrl}); await pgClient.connect();
 pgSchema='proof_'+randomBytes(8).toString('hex');
 await pgClient.query(`CREATE SCHEMA "${pgSchema}"`); await pgClient.query(`SET search_path TO "${pgSchema}"`);
 pg={query:(...args)=>pgClient.query(...args),exec:(sql)=>pgClient.query(sql),close:async()=>{await pgClient.query(`DROP SCHEMA "${pgSchema}" CASCADE`);await pgClient.end();}};
} else pg=await PGlite.create();
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
const expectedKeys=JSON.parse(readFileSync(new URL('./expected-keys.json',import.meta.url)));
assert.deepEqual(sqliteKeys,expectedKeys,'database keys differ from pinned Unicode 15.1 reference');
let i=0;
for (const pair of fixture.pairs) {assert.equal(sqliteKeys[i]===sqliteKeys[i+1],pair.equal,JSON.stringify(pair)); i+=2;}
for (const test of fixture.scopedCases) {
 const [a,b]=test.tags;
 assert.equal(sqliteKeys[i]===sqliteKeys[i+1] && a.scope===b.scope && a.owner===b.owner,test.conflict,JSON.stringify(test)); i+=2;
}
assert.equal(sqliteKeys[i],''); assert.equal(sqliteKeys[i+1],'');
console.log(`PASS SQLite and PostgreSQL: ${fixture.pairs.length} comparison vectors, ${fixture.scopedCases.length} scope cases, 2 empty-name keys; ${inputs.length} identical NFD keys matching the pinned reference.`);
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
// Independent connections race for the same global identity. SQLite serializes
// writers; the loser must see the committed winner and abort in the trigger.
const gate=new SharedArrayBuffer(4);
let ready=0;
const workers=[['Straße',21],['STRASSE',22]].map(([name,id])=>new Worker(new URL('./sqlite-worker.mjs',import.meta.url), {workerData:{name,id,file:sqliteFile,gate}}));
const results=workers.map(worker=>new Promise((resolve,reject)=>{
 worker.on('error',reject); worker.on('message',message=>{
  if(message==='ready') {if(++ready===workers.length) {Atomics.store(new Int32Array(gate),0,1); Atomics.notify(new Int32Array(gate),0);}}
  else resolve(message);
 });
}));
const raced=await Promise.all(results);
assert.equal(raced.filter(r=>r.ok).length,1,JSON.stringify(raced));
assert.match(raced.find(r=>!r.ok).message,/collision/);
assert.equal(sqlite.prepare('SELECT count(*) AS c FROM n WHERE id IN (21,22)').get().c,1);
console.log('PASS SQLite conflicting writes: two independent connections raced; one committed and one was rejected.');
if (pgUrl) {
 const {Client}=await import('pg');
 async function contender(id,name) {
  const c=new Client({connectionString:pgUrl}); await c.connect();
  try {
   await c.query(`SET search_path TO "${pgSchema}"`); await c.query("SET statement_timeout TO '15s'");
   await c.query('BEGIN');
   await c.query('INSERT INTO n(id,name) VALUES($1,$2)',[id,name]);
   await c.query('COMMIT'); return {ok:true};
  } catch (error) { await c.query('ROLLBACK'); return {ok:false,message:error.message}; }
  finally {await c.end();}
 }
 const pgRace=await Promise.all([contender(31,'Straße'),contender(32,'STRASSE')]);
 assert.equal(pgRace.filter(r=>r.ok).length,1,JSON.stringify(pgRace));
 assert.match(pgRace.find(r=>!r.ok).message,/collision/);
 assert.equal((await pg.query('SELECT count(*)::int AS c FROM n WHERE id IN (31,32)')).rows[0].c,1);
 console.log('PASS PostgreSQL conflicting writes: two independent connections raced; one committed and one was rejected.');
} else console.log('PostgreSQL concurrency not tested: embedded PGlite has one connection.');
console.log('LIMITATIONS: change to canonically equivalent spelling of a legacy duplicate is rejected; no Room migration or Android SQL test.');
sqlite.close(); await pg.close(); rmSync(tempDir,{recursive:true,force:true});
