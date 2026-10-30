// Run against the disposable CI PostgreSQL service, never a production database.
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import pg from 'pg';

const base = process.env.MIGRATION_TEST_DATABASE_URL;
if (!base) throw new Error('MIGRATION_TEST_DATABASE_URL is required');
const schema = 'myvault_scope_backup_test';
const client = new pg.Client({ connectionString: base });
await client.connect();
let server;
try {
  await client.query(`CREATE SCHEMA ${schema}`);
  await client.query(`SET search_path TO ${schema}`);
  await client.query(`
    CREATE TABLE settings (id text PRIMARY KEY, app_name text NOT NULL DEFAULT 'MyVault', accent text NOT NULL DEFAULT '#6366f1', mode text NOT NULL DEFAULT 'system', font_size text NOT NULL DEFAULT 'md', radius text NOT NULL DEFAULT 'lg', wallpaper_url text, wallpaper_enabled boolean NOT NULL DEFAULT false, wallpaper_opacity integer NOT NULL DEFAULT 35, wallpaper_blur integer NOT NULL DEFAULT 0, updated_at timestamp NOT NULL DEFAULT now());
    CREATE TABLE spaces (id uuid PRIMARY KEY, name text NOT NULL, description text, icon text NOT NULL DEFAULT '📁', color text NOT NULL DEFAULT '#6366f1', sort_order integer NOT NULL DEFAULT 0, created_at timestamp NOT NULL DEFAULT now());
    CREATE TABLE tags (id uuid PRIMARY KEY, name text NOT NULL CONSTRAINT tags_name_unique UNIQUE, color text NOT NULL DEFAULT '#6366f1', text_color text NOT NULL DEFAULT '#ffffff', reusable boolean NOT NULL DEFAULT true, created_at timestamp NOT NULL DEFAULT now());
    CREATE TABLE entries (id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES spaces(id) ON DELETE CASCADE, title text NOT NULL DEFAULT 'Untitled entry', content text NOT NULL DEFAULT '', cover_image text, pinned boolean NOT NULL DEFAULT false, created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp NOT NULL DEFAULT now());
    CREATE TABLE entry_tags (entry_id uuid NOT NULL REFERENCES entries(id) ON DELETE CASCADE, tag_id uuid NOT NULL REFERENCES tags(id) ON DELETE CASCADE, PRIMARY KEY(entry_id, tag_id));
    INSERT INTO settings(id) VALUES ('singleton');
  `);
  await client.query(await readFile(new URL('../drizzle/0001_tag_scope_foundation.sql', import.meta.url), 'utf8'));
  await client.query(await readFile(new URL('../drizzle/0002_allow_scoped_tag_names.sql', import.meta.url), 'utf8'));
  // Existing case-variant duplicates are preserved when installing guards.
  await client.query("INSERT INTO tags(id, name, reusable) VALUES ('00000000-0000-0000-0000-000000000011', 'Old', false), ('00000000-0000-0000-0000-000000000012', 'old', true)");
  await client.query(await readFile(new URL('../drizzle/0003_tag_scope_guards.sql', import.meta.url), 'utf8'));
  await client.query("UPDATE tags SET color = '#123456' WHERE id = '00000000-0000-0000-0000-000000000011'");
  const concurrent = [new pg.Client({ connectionString: base }), new pg.Client({ connectionString: base })];
  try {
    await Promise.all(concurrent.map(async (c) => { await c.connect(); await c.query(`SET search_path TO ${schema}`); }));
    const results = await Promise.allSettled(concurrent.map((c, i) => c.query("INSERT INTO tags(id,name) VALUES ($1, $2)", [i ? '00000000-0000-0000-0000-000000000021' : '00000000-0000-0000-0000-000000000020', i ? 'Race' : 'race'])));
    assert.equal(results.filter((r) => r.status === 'fulfilled').length, 1, 'exactly one concurrent insertion should succeed');
    assert.equal(results.filter((r) => r.status === 'rejected').length, 1);
  } finally { await Promise.all(concurrent.map((c) => c.end())); }
  await client.query("DELETE FROM tags WHERE name IN ('Old', 'old', 'Race', 'race')");
  const database = new URL(base);
  database.searchParams.set('options', `-csearch_path=${schema}`);
  const url = 'http://127.0.0.1:3198';
  server = spawn('npm', ['run', 'dev', '--', '--hostname', '127.0.0.1', '--port', '3198'], { env: { ...process.env, DATABASE_URL: database.toString() }, stdio: 'inherit', detached: true });
  let ready = false;
  for (let i = 0; i < 60; i++) {
    try { await fetch(url + '/api/health'); ready = true; break; } catch { await new Promise((resolve) => setTimeout(resolve, 1000)); }
  }
  if (!ready) throw new Error('Next.js did not start');
  const post = async (data) => fetch(url + '/api/import', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(data) });
  const local = (id, owner) => ({ id, name: 'Ideas', scope: 'local', ownerSpaceId: owner, reusable: false, color: '#112233', textColor: '#ffffff' });
  const backup = { version: 2, spaces: [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }], tags: [local('ta', 'a'), local('tb', 'b')], entries: [{ id: 'ea', spaceId: 'a', content: 'A content', tags: [local('ta', 'a')] }, { id: 'eb', spaceId: 'b', content: 'B content', tags: [local('tb', 'b')] }] };
  let res = await post(backup);
  assert.equal(res.status, 200, await res.text());
  res = await fetch(url + '/api/export');
  const exported = await res.json();
  assert.equal(exported.version, 2);
  assert.equal(exported.tags.length, 2);
  for (const entry of exported.entries) {
    assert.equal(entry.tags[0].scope, 'local');
    assert.equal(entry.tags[0].ownerSpaceId, entry.spaceId);
    assert.equal(entry.tags[0].reusable, false);
  }
  const [first, second] = exported.entries;
  const badEdit = await fetch(url + '/api/tags/' + first.tags[0].id, {
    method: 'PATCH', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ scope: 'local', ownerSpaceId: second.spaceId }),
  });
  assert.equal(badEdit.status, 409, 'scope edit must preserve existing entry associations');
  const globalEdit = await fetch(url + '/api/tags/' + first.tags[0].id, {
    method: 'PATCH', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ scope: 'global', ownerSpaceId: null }),
  });
  assert.equal(globalEdit.status, 200, await globalEdit.text());
  const restoreEdit = await fetch(url + '/api/tags/' + first.tags[0].id, {
    method: 'PATCH', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ scope: 'local', ownerSpaceId: first.spaceId }),
  });
  assert.equal(restoreEdit.status, 200, await restoreEdit.text());
  const noScope = await fetch(url + '/api/tags', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'New' }) });
  assert.equal(noScope.status, 400);
  const badAttach = await fetch(url + '/api/entries/' + first.id, {
    method: 'PATCH', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ tagIds: [second.tags[0].id] }),
  });
  assert.equal(badAttach.status, 400);
  const before = (await client.query('SELECT count(*) FROM entries')).rows[0].count;
  const invalid = structuredClone(backup);
  invalid.entries[0].tags[0] = local('tb', 'b');
  res = await post(invalid);
  assert.equal(res.status, 400);
  assert.equal((await client.query('SELECT count(*) FROM entries')).rows[0].count, before);
  res = await post({ version: 1, spaces: [], tags: [{ id: 'legacy', name: 'Legacy', reusable: false }], entries: [] });
  assert.equal(res.status, 200, await res.text());
  const legacy = (await client.query("SELECT scope, owner_space_id, reusable FROM tags WHERE name = 'Legacy'" )).rows[0];
  assert.deepEqual(legacy, { scope: 'global', owner_space_id: null, reusable: false });
  const ambiguous = JSON.parse(await readFile(new URL('../tests/fixtures/legacy-v1-ambiguous.json', import.meta.url), 'utf8'));
  const beforeAmbiguous = await client.query('SELECT (SELECT count(*) FROM spaces) AS spaces, (SELECT count(*) FROM tags) AS tags, (SELECT count(*) FROM entries) AS entries, (SELECT count(*) FROM entry_tags) AS links');
  res = await post(ambiguous);
  assert.equal(res.status, 400);
  assert.match((await res.json()).error, /Ambiguous imported tags named/i);
  assert.deepEqual((await client.query('SELECT (SELECT count(*) FROM spaces) AS spaces, (SELECT count(*) FROM tags) AS tags, (SELECT count(*) FROM entries) AS entries, (SELECT count(*) FROM entry_tags) AS links')).rows, beforeAmbiguous.rows);
  const exactDuplicates = structuredClone(ambiguous);
  exactDuplicates.tags[1].name = 'Ideas';
  exactDuplicates.entries[0].tags[1].name = 'Ideas';
  res = await post(exactDuplicates);
  assert.equal(res.status, 400);
  assert.match((await res.json()).error, /Ambiguous imported tags named/i);
  assert.deepEqual((await client.query('SELECT (SELECT count(*) FROM spaces) AS spaces, (SELECT count(*) FROM tags) AS tags, (SELECT count(*) FROM entries) AS entries, (SELECT count(*) FROM entry_tags) AS links')).rows, beforeAmbiguous.rows);
  // An ID-less legacy link must not be resolved by picking the last of two IDs.
  ambiguous.entries[0].tags = [{ name: 'ideas' }];
  res = await post(ambiguous);
  assert.equal(res.status, 400);
  assert.match((await res.json()).error, /Ambiguous legacy tag.*has no ID/i);
  const beforeDefaults = (await client.query('SELECT count(*) FROM tags')).rows[0].count;
  res = await post({ version: 1, spaces: [], tags: [{ id: 'default', name: 'Legacy defaults' }], entries: [] });
  assert.equal(res.status, 200, await res.text());
  const defaults = (await client.query("SELECT scope, owner_space_id, reusable, lower(color) AS color, lower(text_color) AS text_color FROM tags WHERE name = 'Legacy defaults' ")).rows[0];
  assert.deepEqual(defaults, { scope: 'global', owner_space_id: null, reusable: true, color: '#6366f1', text_color: '#ffffff' });
  assert.equal(Number((await client.query('SELECT count(*) FROM tags')).rows[0].count), Number(beforeDefaults) + 1);
  if (process.env.ANDROID_BACKUP_FILE && process.env.WEB_BACKUP_FILE) {
    // Exchange actual exporter output, not a reconstructed DTO fixture.
    await client.query('DELETE FROM entry_tags; DELETE FROM entries; DELETE FROM tags; DELETE FROM spaces');
    await client.query("INSERT INTO spaces(id,name) VALUES ('00000000-0000-0000-0000-0000000000a1','Cross A'),('00000000-0000-0000-0000-0000000000b1','Cross B')");
    const androidBytes = await readFile(process.env.ANDROID_BACKUP_FILE, 'utf8');
    const android = JSON.parse(androidBytes);
    assert.equal(android.version, 2);
    res = await fetch(url + '/api/import', { method: 'POST', headers: { 'content-type': 'application/json' }, body: androidBytes });
    assert.equal(res.status, 200, await res.text());
    res = await fetch(url + '/api/export');
    assert.equal(res.status, 200);
    const webBytes = await res.text();
    const web = JSON.parse(webBytes);
    assert.equal(web.version, 2);
    assert.equal(web.entries.length, 2);
    assert.equal(web.tags.length, 3);
    for (const [suffix, destination, reusable] of [['a', '00000000-0000-0000-0000-0000000000a1', false], ['b', '00000000-0000-0000-0000-0000000000b1', true]]) {
      const entry = web.entries.find((item) => item.title === `Entry ${suffix}`);
      assert.equal(entry.spaceId, destination);
      assert.notEqual(entry.id, `source-entry-${suffix}`);
      assert.equal(entry.tags.length, 2);
      const local = entry.tags.find((tag) => tag.name === 'Ideas');
      assert.equal(local.scope, 'local');
      assert.equal(local.ownerSpaceId, destination);
      assert.equal(local.reusable, reusable);
      const global = entry.tags.find((tag) => tag.name === 'Shared');
      assert.equal(global.scope, 'global');
      assert.equal(global.ownerSpaceId, null);
      assert.equal(global.reusable, false);
    }
    await writeFile(process.env.WEB_BACKUP_FILE, webBytes);
    console.log('Actual Android exporter → web importer/exporter validated; web file saved for Android import.');
  }
  console.log('Scoped and legacy backup API tests passed.');
} finally {
  if (server?.pid) { try { process.kill(-server.pid, 'SIGTERM'); } catch {} }
  await client.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
  await client.end();
}
