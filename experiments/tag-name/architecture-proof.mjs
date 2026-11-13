// Isolated architecture probe only. Do not use with app/user databases.
// Runs a Unicode 15.1 table-driven identity function inside Node's bundled
// SQLite and compares it to the pinned fixture. It does NOT prove Android API
// availability, Room connection registration, Neon, or PostgreSQL production.
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const ucd = JSON.parse(readFileSync(new URL('./ucd-15.1.json', import.meta.url)));
assert.equal(ucd.unicode, '15.1.0');
const decomp = new Map(), fold = new Map(), ccc = new Map(), compose = new Map();
for (const [cp, nfd, f, cls] of ucd.rows) {
  if (nfd !== null) decomp.set(cp, [...nfd].map(c => c.codePointAt(0)));
  if (f !== null) fold.set(cp, [...f].map(c => c.codePointAt(0)));
  if (cls) ccc.set(cp, cls);
}
for (const [a, b, value] of ucd.compositions) compose.set(`${a},${b}`, value.codePointAt(0));
const white = new Set([9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288]);
function composeNfc(points) {
  const ordered = [];
  for (const cp of points) {
    const cls = ccc.get(cp) ?? 0;
    let at = ordered.length;
    if (cls) while (at > 0) {
      const prev = ccc.get(ordered[at - 1]) ?? 0;
      if (prev === 0 || prev <= cls) break;
      at--;
    }
    ordered.splice(at, 0, cp);
  }
  const out = []; let starterAt = -1, starter = -1, lastClass = 0;
  for (const cp of ordered) {
    const cls = ccc.get(cp) ?? 0;
    const combined = starterAt >= 0 && (lastClass === 0 || lastClass < cls) ? compose.get(`${starter},${cp}`) : undefined;
    if (combined !== undefined) { out[starterAt] = combined; starter = combined; continue; }
    if (cls === 0) { starterAt = out.length; starter = cp; }
    out.push(cp); lastClass = cls;
  }
  return out;
}
function identity(s) {
  if (typeof s !== 'string') throw new TypeError('tag name must be text');
  const spaced = [];
  for (const char of s) {
    const cp = char.codePointAt(0);
    if (white.has(cp)) { if (spaced.length && spaced.at(-1) !== 32) spaced.push(32); }
    else spaced.push(cp);
  }
  if (spaced.at(-1) === 32) spaced.pop();
  const nfc = composeNfc(spaced);
  const folded = [];
  for (const cp of nfc) folded.push(...(fold.get(cp) ?? [cp]));
  return String.fromCodePoint(...composeNfc(folded));
}

const fixture = JSON.parse(readFileSync(new URL('../../tests/fixtures/tag-name-policy-vectors.json', import.meta.url)));
const expected = JSON.parse(readFileSync(new URL('./expected-keys.json', import.meta.url)));
const expectedNfc = JSON.parse(readFileSync(new URL('./expected-identity-nfc.json', import.meta.url)));
const inputs = fixture.pairs.flatMap(p => [p.left, p.right]).concat(fixture.scopedCases.flatMap(c => c.tags.map(t => t.name)), ['', ' \u00a0\t', 'Café']);
assert.equal(inputs.length, expected.length);
// The existing expected-keys artifact is the same policy's NFD comparison key.
// Compare database outputs by normalizing those generated NFC values to NFD.
for (let i = 0; i < inputs.length; i++) {
  const got = identity(inputs[i]);
  assert.equal(got, expectedNfc[i], `Unicode 15.1 NFC identity input ${i}: ${JSON.stringify(inputs[i])}`);
  assert.equal([...got.normalize('NFD')].join(''), expected[i], `NFD comparison key input ${i}: ${JSON.stringify(inputs[i])}`);
}
let offset = 0;
for (const p of fixture.pairs) {
  assert.equal(identity(p.left) === identity(p.right), p.equal, JSON.stringify(p)); offset += 2;
}
for (const c of fixture.scopedCases) {
  const [a, b] = c.tags;
  assert.equal(identity(a.name) === identity(b.name) && a.scope === b.scope && a.owner === b.owner, c.conflict, JSON.stringify(c)); offset += 2;
}

const dir = mkdtempSync(join(tmpdir(), 'mv-unicode-identity-'));
const file = join(dir, 'proof.sqlite');
const db = new DatabaseSync(file);
db.function('tag_identity_15_1', { deterministic: true }, identity);
db.exec(`CREATE TABLE tags(
 id INTEGER PRIMARY KEY, name TEXT NOT NULL, scope TEXT NOT NULL CHECK(scope IN ('global','local')),
 owner TEXT, reusable INTEGER NOT NULL CHECK(reusable IN (0,1)), identity TEXT NOT NULL,
 CHECK(identity = tag_identity_15_1(name)), CHECK(identity <> ''),
 CHECK((scope='global' AND owner IS NULL) OR (scope='local' AND owner IS NOT NULL))
);
CREATE UNIQUE INDEX tag_identity_scope_uq ON tags(scope, COALESCE(owner,''), identity);
CREATE TABLE spaces(id TEXT PRIMARY KEY);
CREATE TABLE entries(id TEXT PRIMARY KEY,space_id TEXT NOT NULL REFERENCES spaces(id));
CREATE TABLE entry_tags(entry_id TEXT NOT NULL REFERENCES entries(id),tag_id INTEGER NOT NULL REFERENCES tags(id),PRIMARY KEY(entry_id,tag_id));
CREATE TRIGGER entry_tag_scope_insert BEFORE INSERT ON entry_tags BEGIN
 SELECT CASE WHEN EXISTS(SELECT 1 FROM tags t JOIN entries e ON e.id=NEW.entry_id WHERE t.id=NEW.tag_id AND t.scope='local' AND t.owner IS NOT e.space_id) THEN RAISE(ABORT,'Local tag belongs to another Space') END;
END;
CREATE TRIGGER entry_tag_scope_update BEFORE UPDATE ON entry_tags BEGIN
 SELECT CASE WHEN EXISTS(SELECT 1 FROM tags t JOIN entries e ON e.id=NEW.entry_id WHERE t.id=NEW.tag_id AND t.scope='local' AND t.owner IS NOT e.space_id) THEN RAISE(ABORT,'Local tag belongs to another Space') END;
END;
CREATE TRIGGER entry_space_scope_update BEFORE UPDATE OF space_id ON entries BEGIN
 SELECT CASE WHEN EXISTS(SELECT 1 FROM entry_tags et JOIN tags t ON t.id=et.tag_id WHERE et.entry_id=NEW.id AND t.scope='local' AND t.owner IS NOT NEW.space_id) THEN RAISE(ABORT,'Entry has a Local tag from another Space') END;
END;`);
const insert = db.prepare('INSERT INTO tags(name,scope,owner,reusable,identity) VALUES(?,?,?,?,tag_identity_15_1(?))');
const add = (name, scope, owner, reusable=1) => insert.run(name, scope, owner, reusable, name);
add('Ideas', 'local', 'space-a', 0);
add('IDEAS', 'local', 'space-b', 1);
add('Ideas', 'global', null, 0);
assert.throws(() => add('ideas', 'local', 'space-a'), /UNIQUE constraint failed/);
assert.throws(() => add('ideas', 'global', null), /UNIQUE constraint failed/);
assert.throws(() => db.prepare("INSERT INTO tags(name,scope,owner,reusable,identity) VALUES('Different','global',NULL,1,'forged')").run(), /CHECK constraint failed/);
assert.throws(() => db.prepare("INSERT INTO tags(name,scope,owner,reusable,identity) VALUES('Straße','global',NULL,1,'wrong-key')").run(), /CHECK constraint failed/);
add('Straße', 'global', null, 1);
add('Straße', 'local', 'space-a', 1);
const before = db.prepare('SELECT id,name FROM tags ORDER BY id').all();
assert.throws(() => db.prepare("UPDATE tags SET name='STRASSE',identity=tag_identity_15_1('STRASSE') WHERE id=1").run(), /UNIQUE constraint failed/);
assert.deepEqual(db.prepare('SELECT id,name FROM tags ORDER BY id').all(), before, 'failed rename must be atomic');
assert.equal(db.prepare("SELECT reusable FROM tags WHERE name='Ideas' AND scope='local'").get().reusable, 0);
db.exec("INSERT INTO spaces VALUES('space-a'),('space-b'); INSERT INTO entries VALUES('entry-a','space-a'),('entry-b','space-b')");
const localA=db.prepare("SELECT id FROM tags WHERE name='Ideas' AND scope='local' AND owner='space-a'").get().id;
const localB=db.prepare("SELECT id FROM tags WHERE name='IDEAS' AND scope='local' AND owner='space-b'").get().id;
const globalId=db.prepare("SELECT id FROM tags WHERE name='Ideas' AND scope='global'").get().id;
db.prepare('INSERT INTO entry_tags VALUES(?,?)').run('entry-a',localA);
db.prepare('INSERT INTO entry_tags VALUES(?,?)').run('entry-b',localB);
db.prepare('INSERT INTO entry_tags VALUES(?,?)').run('entry-a',globalId);
db.prepare('INSERT INTO entry_tags VALUES(?,?)').run('entry-b',globalId);
assert.throws(()=>db.prepare('INSERT INTO entry_tags VALUES(?,?)').run('entry-b',localA),/Local tag belongs/);
assert.throws(()=>db.prepare("UPDATE entries SET space_id='space-b' WHERE id='entry-a'").run(),/Entry has a Local tag/);
assert.equal(db.prepare('SELECT count(*) AS c FROM entry_tags').get().c,4);
db.exec("CREATE TABLE legacy_tags(id INTEGER PRIMARY KEY,name TEXT NOT NULL,scope TEXT NOT NULL,owner TEXT); INSERT INTO legacy_tags VALUES(1,'Café','global',NULL),(2,'Café','global',NULL),(3,'Ideas','local','space-a'),(4,'IDEAS','local','space-a'),(5,'Ideas','local','space-b')");
const legacyGroups=db.prepare(`SELECT scope,COALESCE(owner,'') owner,tag_identity_15_1(name) identity,group_concat(id) ids,count(*) n FROM legacy_tags GROUP BY scope,COALESCE(owner,''),tag_identity_15_1(name) HAVING count(*)>1 ORDER BY scope,owner`).all();
assert.deepEqual(legacyGroups.map(g=>g.ids),['1,2','3,4']);
assert.equal(db.prepare('SELECT count(*) AS c FROM legacy_tags').get().c,5,'legacy duplicate scan must not rewrite or remove rows');
const rounds = 20000;
const timed = db.prepare('SELECT tag_identity_15_1(?) AS k');
const sample = ['Tag', 'Project café 2026', 'x'.repeat(80), 'a'+'́'.repeat(64), 'Café', 'Straße', 'ΟΣ', 'İ', '𐐀', '\u2003one\u00a0\t two\u202f'];
const t0 = performance.now();
for (let i = 0; i < rounds; i++) timed.get(sample[i % sample.length]);
const identityNsEach = (performance.now() - t0) * 1e6 / rounds;
const writeRows = 500;
const writeStart = performance.now();
db.exec('BEGIN IMMEDIATE');
for (let i=0;i<writeRows;i++) add(`bench-${i}`, 'local', 'space-benchmark', Number(i%2===0));
db.exec('COMMIT');
const insert500Ms = performance.now()-writeStart;
const renameStart = performance.now();
db.prepare('UPDATE tags SET name=?, identity=tag_identity_15_1(?) WHERE name=?').run('bench-renamed', 'bench-renamed', 'bench-0');
const renameMs = performance.now()-renameStart;
const used = process.memoryUsage();
const databaseBytes = statSync(file).size;
console.log(JSON.stringify({
  status: 'PASS', runtime: process.version, sqlite: db.prepare('select sqlite_version() v').get().v,
  unicodeData: ucd.unicode, fixturePairs: fixture.pairs.length, scopeCases: fixture.scopedCases.length,
  fixtureInputs: inputs.length, dbCheckAndUnique: 'PASS', scopeAndReusable: 'PASS', assignmentIsolationAndGlobalAvailability: 'PASS', legacyDuplicateReportWithoutRewrite: 'PASS', failedRenameAtomic: 'PASS',
  repeatedIdentityCalls: rounds, identityMeanNs: Math.round(identityNsEach), insert500Ms: Number(insert500Ms.toFixed(3)), renameOneMs: Number(renameMs.toFixed(3)), sqliteDatabaseBytes: databaseBytes, heapUsedBytes: used.heapUsed, rssBytes: used.rss,
  note: 'Host Node SQLite/JS UDF proxy only; not an Android, Room, Neon, or PostgreSQL result.'
}, null, 2));
db.close(); rmSync(dir, { recursive: true, force: true });
