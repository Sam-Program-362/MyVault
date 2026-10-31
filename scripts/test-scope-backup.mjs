// Disposable PostgreSQL/API boundary test. Never point this at production.
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
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
    INSERT INTO spaces(id,name) VALUES ('00000000-0000-0000-0000-0000000000a1','Space A'), ('00000000-0000-0000-0000-0000000000b1','Space B');
    INSERT INTO tags(id,name,reusable) VALUES
      ('00000000-0000-0000-0000-000000000011','Legacy',false),
      ('00000000-0000-0000-0000-000000000012','legacy',true),
      ('00000000-0000-0000-0000-000000000013','Local A',true),
      ('00000000-0000-0000-0000-000000000014','Local B',false);
    INSERT INTO entries(id,space_id,title) VALUES
      ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000a1','Entry A'),
      ('00000000-0000-0000-0000-0000000000e2','00000000-0000-0000-0000-0000000000b1','Entry B');
    INSERT INTO entry_tags VALUES ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-000000000013');
  `);
  await client.query(await readFile(new URL('../drizzle/0001_tag_scope_foundation.sql', import.meta.url), 'utf8'));
  await client.query("UPDATE tags SET scope='local', owner_space_id='00000000-0000-0000-0000-0000000000a1' WHERE id='00000000-0000-0000-0000-000000000013'");
  await client.query("UPDATE tags SET scope='local', owner_space_id='00000000-0000-0000-0000-0000000000b1' WHERE id='00000000-0000-0000-0000-000000000014'");
  await client.query(await readFile(new URL('../drizzle/0002_allow_scoped_tag_names.sql', import.meta.url), 'utf8'));
  await client.query(await readFile(new URL('../drizzle/0003_tag_scope_guards.sql', import.meta.url), 'utf8'));

  const database = new URL(base);
  database.searchParams.set('options', `-csearch_path=${schema}`);
  const url = 'http://127.0.0.1:3198';
  server = spawn('npm', ['run', 'dev', '--', '--hostname', '127.0.0.1', '--port', '3198'], { env: { ...process.env, DATABASE_URL: database.toString() }, stdio: 'inherit', detached: true });
  let ready = false;
  for (let i = 0; i < 60; i++) {
    try { await fetch(url + '/api/health'); ready = true; break; } catch { await new Promise((resolve) => setTimeout(resolve, 1000)); }
  }
  if (!ready) throw new Error('Next.js did not start');
  const initialCounts = async () => (await client.query('SELECT (SELECT count(*) FROM spaces) AS spaces, (SELECT count(*) FROM tags) AS tags, (SELECT count(*) FROM entries) AS entries, (SELECT count(*) FROM entry_tags) AS links')).rows[0];
  const getTags = await fetch(url + '/api/tags');
  const tags = await getTags.json();
  assert.equal(tags.length, 4);
  assert.equal(tags.find((tag) => tag.id.endsWith('0011')).scope, 'global');
  assert.equal(tags.find((tag) => tag.id.endsWith('0011')).reusable, false);
  assert.equal(tags.find((tag) => tag.id.endsWith('0013')).ownerSpaceId, '00000000-0000-0000-0000-0000000000a1');

  const noScope = await fetch(url + '/api/tags', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'New' }) });
  assert.equal(noScope.status, 400);
  const create = await fetch(url + '/api/tags', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'New', scope: 'local', ownerSpaceId: '00000000-0000-0000-0000-0000000000a1' }) });
  assert.equal(create.status, 409);
  assert.match((await create.json()).error, /Unicode 15.1/);
  const identityEdit = await fetch(url + '/api/tags/00000000-0000-0000-0000-000000000013', {
    method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ ownerSpaceId: '00000000-0000-0000-0000-0000000000b1' }),
  });
  assert.equal(identityEdit.status, 409);
  const metadataEdit = await fetch(url + '/api/tags/00000000-0000-0000-0000-000000000013', {
    method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ reusable: false, color: '#123456' }),
  });
  assert.equal(metadataEdit.status, 200);
  assert.equal((await metadataEdit.json()).scope, 'local');

  const wrongSpaceAttach = await fetch(url + '/api/entries/00000000-0000-0000-0000-0000000000e2', {
    method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ tagIds: ['00000000-0000-0000-0000-000000000013'] }),
  });
  assert.equal(wrongSpaceAttach.status, 400);
  const rightSpaceAttach = await fetch(url + '/api/entries/00000000-0000-0000-0000-0000000000e1', {
    method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ tagIds: ['00000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000011'] }),
  });
  assert.equal(rightSpaceAttach.status, 200);
  const filterA = await fetch(url + '/api/entries?spaceId=00000000-0000-0000-0000-0000000000a1&tagId=00000000-0000-0000-0000-000000000013').then((res) => res.json());
  const filterB = await fetch(url + '/api/entries?spaceId=00000000-0000-0000-0000-0000000000b1&tagId=00000000-0000-0000-0000-000000000013').then((res) => res.json());
  const filterAll = await fetch(url + '/api/entries?tagId=00000000-0000-0000-0000-000000000013').then((res) => res.json());
  assert.equal(filterA.length, 1);
  assert.equal(filterB.length, 0);
  assert.equal(filterAll.length, 0);

  const importUnknown = async (payload) => fetch(url + '/api/import', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) });
  const unknownBackup = {
    version: 2,
    spaces: [{ id: 'new-a', name: 'New A' }],
    tags: [{ id: 'new-tag', name: 'Imported', scope: 'local', ownerSpaceId: 'new-a', reusable: false }],
    entries: [{ id: 'new-entry', spaceId: 'new-a', content: 'must roll back', tags: [{ id: 'new-tag', name: 'Imported', scope: 'local', ownerSpaceId: 'new-a', reusable: false }] }],
  };
  const beforeRejectedImport = await initialCounts();
  let response = await importUnknown(unknownBackup);
  assert.equal(response.status, 400);
  assert.match((await response.json()).error, /new tag identities|Unicode 15.1/i);
  assert.deepEqual(await initialCounts(), beforeRejectedImport, 'rejected imports commit no rows or links');

  if (process.env.ANDROID_BACKUP_FILE) {
    const androidBytes = await readFile(process.env.ANDROID_BACKUP_FILE, 'utf8');
    const androidBackup = JSON.parse(androidBytes);
    assert.equal(androidBackup.version, 2);
    response = await fetch(url + '/api/import', { method: 'POST', headers: { 'content-type': 'application/json' }, body: androidBytes });
    assert.equal(response.status, 400, 'cross-platform import with unknown tag IDs fails closed');
    assert.deepEqual(await initialCounts(), beforeRejectedImport);
  }

  const exported = await fetch(url + '/api/export').then((res) => res.json());
  assert.equal(exported.version, 2);
  assert.equal(exported.tags.length, 4);
  assert.equal(exported.tags.find((tag) => tag.id.endsWith('0013')).scope, 'local');
  assert.equal(exported.tags.find((tag) => tag.id.endsWith('0013')).reusable, false);
  assert.equal(exported.entries.find((entry) => entry.id.endsWith('00e1')).tags.length, 2);
  assert.deepEqual(await initialCounts(), beforeRejectedImport);
  const deleted = await fetch(url + '/api/tags/00000000-0000-0000-0000-000000000013', { method: 'DELETE' });
  assert.equal(deleted.status, 200);
  assert.equal((await client.query("SELECT count(*) FROM entry_tags WHERE tag_id='00000000-0000-0000-0000-000000000013'")).rows[0].count, '0');
  assert.equal((await client.query('SELECT count(*) FROM tags')).rows[0].count, '3');
  console.log('Scope-aware export/filter/attachment/deletion checks pass; unsupported creation and cross-platform tag imports fail closed without data changes.');
} finally {
  if (server?.pid) { try { process.kill(-server.pid, 'SIGTERM'); } catch {} }
  await client.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
  await client.end();
}
