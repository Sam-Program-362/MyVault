// Run against the disposable CI PostgreSQL service, never a production database.
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
  `);
  await client.query(await readFile(new URL('../drizzle/0001_tag_scope_foundation.sql', import.meta.url), 'utf8'));
  await client.query(await readFile(new URL('../drizzle/0002_allow_scoped_tag_names.sql', import.meta.url), 'utf8'));
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
  console.log('Scoped and legacy backup API tests passed.');
} finally {
  if (server?.pid) { try { process.kill(-server.pid, 'SIGTERM'); } catch {} }
  await client.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
  await client.end();
}
