// Disposable PostgreSQL/API boundary test. Never point this at production.
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { deflateSync } from 'node:zlib';

const base = process.env.MIGRATION_TEST_DATABASE_URL;
if (!base) throw new Error('MIGRATION_TEST_DATABASE_URL is required');
const schema = 'myvault_scope_backup_test';
const client = new pg.Client({ connectionString: base });
await client.connect();
let server;
async function waitForApi(url) {
  for (let i = 0; i < 60; i++) {
    try { await fetch(url + '/api/health'); return; } catch { await new Promise((resolve) => setTimeout(resolve, 1000)); }
  }
  throw new Error('Next.js did not start');
}
function makePng(pixel = [255, 0, 0, 255]) {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    return c >>> 0;
  });
  const crc32 = (buffer) => {
    let crc = 0xffffffff;
    for (const byte of buffer) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const typeBytes = Buffer.from(type);
    const size = Buffer.alloc(4); size.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])));
    return Buffer.concat([size, typeBytes, data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(1, 0); ihdr.writeUInt32BE(1, 4);
  ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(Buffer.from([0, ...pixel]))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
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
  await client.query(await readFile(new URL('../drizzle/0004_durable_media.sql', import.meta.url), 'utf8'));

  const database = new URL(base);
  database.searchParams.set('options', `-csearch_path=${schema}`);
  const url = 'http://127.0.0.1:3198';
  server = spawn('npm', ['run', 'dev', '--', '--hostname', '127.0.0.1', '--port', '3198'], { env: { ...process.env, DATABASE_URL: database.toString() }, stdio: 'inherit', detached: true });
  await waitForApi(url);
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

  const png = makePng();
  const mediaForm = new FormData();
  mediaForm.append('file', new Blob([png], { type: 'image/png' }), 'tiny.png');
  let mediaResponse = await fetch(url + '/api/media', { method: 'POST', body: mediaForm });
  assert.equal(mediaResponse.status, 201, 'valid raster image is accepted');
  const storedMedia = await mediaResponse.json();
  assert.match(storedMedia.url, /^\/api\/media\/[0-9a-f-]{36}$/i);
  let downloaded = await fetch(url + storedMedia.url);
  assert.equal(downloaded.status, 200);
  assert.equal(downloaded.headers.get('content-type'), 'image/png');
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), png);
  const malformedForm = new FormData();
  malformedForm.append('file', new Blob([Buffer.from('<svg/>')], { type: 'image/svg+xml' }), 'vector.svg');
  assert.equal((await fetch(url + '/api/media', { method: 'POST', body: malformedForm })).status, 400, 'SVG and malformed images are rejected');
  const mismatchForm = new FormData();
  mismatchForm.append('file', new Blob([png], { type: 'image/jpeg' }), 'mislabeled.jpg');
  assert.equal((await fetch(url + '/api/media', { method: 'POST', body: mismatchForm })).status, 400, 'declared MIME must match sniffed image bytes');
  const oversizedForm = new FormData();
  oversizedForm.append('file', new Blob([Buffer.alloc(2 * 1024 * 1024 + 1)], { type: 'image/png' }), 'large.png');
  assert.equal((await fetch(url + '/api/media', { method: 'POST', body: oversizedForm })).status, 413, 'oversized image is rejected before storage');

  const portableUri = `data:image/png;base64,${png.toString('base64')}`;
  let mediaImport = await fetch(url + '/api/import', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      version: 2,
      spaces: [{ id: 'portable-space', name: 'Space A' }],
      entries: [{ id: 'portable-entry', spaceId: 'portable-space', title: 'Portable image', content: `Before ![one](${portableUri}) after.\n![external](https://example.org/photo.png)` }],
    }),
  });
  assert.equal(mediaImport.status, 200, await mediaImport.text());
  const importedEntry = (await client.query("SELECT content FROM entries WHERE title='Portable image'")).rows[0].content;
  assert.match(importedEntry, /Before !\[one\]\(\/api\/media\//);
  assert.match(importedEntry, /after\.\n!\[external\]\(https:\/\/example\.org\/photo\.png\)/);
  const importedMediaUrl = /\]\((\/api\/media\/[0-9a-f-]{36})\)/i.exec(importedEntry)[1];
  downloaded = await fetch(url + importedMediaUrl);
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), png, 'portable bytes are durable and retrievable');

  const directAtomicBytes = makePng([255, 255, 0, 255]);
  const directAtomicCounts = await initialCounts();
  const directAtomicMediaCount = (await client.query('SELECT count(*) FROM media_assets')).rows[0].count;
  const directAtomicImport = await fetch(url + '/api/import', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      version: 2, spaces: [{ id: 'direct-atomic-space', name: 'Direct Atomic Failure' }],
      tags: [{ id: '99999999-9999-4999-8999-999999999998', name: 'Unknown direct-media tag' }],
      entries: [{ id: 'direct-atomic-entry', spaceId: 'direct-atomic-space', content: `![uncommitted](data:image/png;base64,${directAtomicBytes.toString('base64')})` }],
    }),
  });
  assert.equal(directAtomicImport.status, 400);
  assert.deepEqual(await initialCounts(), directAtomicCounts, 'failed direct portable import rolls back entries and spaces');
  assert.equal((await client.query('SELECT count(*) FROM media_assets')).rows[0].count, directAtomicMediaCount, 'failed direct portable import rolls back image bytes too');

  const stagedToken = crypto.randomUUID();
  const stagedPng = makePng([0, 255, 0, 255]);
  const stagedForm = new FormData();
  stagedForm.append('file', new Blob([stagedPng], { type: 'image/png' }), 'staged.png');
  stagedForm.append('stagingToken', stagedToken);
  mediaResponse = await fetch(url + '/api/media', { method: 'POST', body: stagedForm });
  assert.equal(mediaResponse.status, 201);
  const stagedMedia = await mediaResponse.json();
  assert.equal((await fetch(url + stagedMedia.url)).status, 404, 'staged backup media stays hidden before import commit');
  const beforeAtomicMedia = (await client.query('SELECT count(*) FROM media_assets')).rows[0].count;
  const rejectedMediaImport = await fetch(url + '/api/import', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      version: 2, mediaStagingToken: stagedToken,
      spaces: [{ id: 'atomic-space', name: 'Atomic Failure Space' }],
      tags: [{ id: '99999999-9999-4999-8999-999999999999', name: 'Unknown portable tag' }],
      entries: [{ id: 'atomic-entry', spaceId: 'atomic-space', content: `Keep ![image](${stagedMedia.url})` }],
    }),
  });
  assert.equal(rejectedMediaImport.status, 400, 'unsupported tag identity aborts the complete import');
  assert.equal((await client.query('SELECT count(*) FROM media_assets')).rows[0].count, beforeAtomicMedia, 'failed transaction keeps staged blob and rolls back its data changes');
  assert.equal((await fetch(url + stagedMedia.url)).status, 404, 'failed import never exposes staged media');
  const cleanup = await fetch(url + stagedMedia.url, { method: 'DELETE', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ stagingToken: stagedToken }) });
  assert.equal(cleanup.status, 200);
  assert.equal((await client.query('SELECT count(*) FROM media_assets')).rows[0].count, String(Number(beforeAtomicMedia) - 1));

  const committedToken = crypto.randomUUID();
  const committedPng = makePng([0, 0, 255, 255]);
  const committedForm = new FormData();
  committedForm.append('file', new Blob([committedPng], { type: 'image/png' }), 'commit.png');
  committedForm.append('stagingToken', committedToken);
  mediaResponse = await fetch(url + '/api/media', { method: 'POST', body: committedForm });
  const committedMedia = await mediaResponse.json();
  const committedImport = await fetch(url + '/api/import', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ version: 2, mediaStagingToken: committedToken, spaces: [{ id: 'commit-space', name: 'Space A' }], entries: [{ id: 'commit-entry', spaceId: 'commit-space', content: `![commit](${committedMedia.url})` }] }),
  });
  assert.equal(committedImport.status, 200, await committedImport.text());
  assert.equal((await fetch(url + committedMedia.url)).status, 200, 'staged media becomes visible only after the entry transaction commits');
  assert.equal((await client.query('SELECT staging_token FROM media_assets WHERE id=$1', [committedMedia.id])).rows[0].staging_token, null);

  // A fresh Next.js process against the same disposable PostgreSQL schema must still serve the bytes.
  const previousServer = server;
  try { process.kill(-previousServer.pid, 'SIGTERM'); } catch {}
  await new Promise((resolve) => {
    if (previousServer.exitCode !== null || previousServer.signalCode !== null) resolve();
    else previousServer.once('exit', resolve);
  });
  server = spawn('npm', ['run', 'dev', '--', '--hostname', '127.0.0.1', '--port', '3198'], { env: { ...process.env, DATABASE_URL: database.toString() }, stdio: 'inherit', detached: true });
  await waitForApi(url);
  downloaded = await fetch(url + committedMedia.url);
  assert.equal(downloaded.status, 200, 'media persists after the web process restarts');
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), committedPng);

  const importUnknown = async (payload) => fetch(url + '/api/import', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) });
  const unknownBackup = {
    version: 2,
    spaces: [{ id: 'new-a', name: 'New A' }],
    tags: [{ id: 'new-tag', name: 'Imported', scope: 'local', ownerSpaceId: 'new-a', reusable: false }],
    entries: [{ id: 'new-entry', spaceId: 'new-a', content: 'must roll back', tags: [{ id: 'new-tag', name: 'Imported', scope: 'local', ownerSpaceId: 'new-a', reusable: false }] }],
  };
  let beforeRejectedImport = await initialCounts();
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
    const mediaOnlyAndroidBackup = {
      ...androidBackup,
      tags: [],
      entries: androidBackup.entries.map((entry) => ({ ...entry, tags: [] })),
    };
    response = await importUnknown(mediaOnlyAndroidBackup);
    assert.equal(response.status, 200, await response.text());
    const androidEntry = (await client.query("SELECT content FROM entries WHERE title='Entry a'")).rows[0].content;
    assert.match(androidEntry, /Before !\[diagram\]\(\/api\/media\//);
    assert.match(androidEntry, /!\[external\]\(https:\/\/example\.org\/image\.png\)/);
    const androidMediaUrl = /\]\((\/api\/media\/[0-9a-f-]{36})\)/i.exec(androidEntry)[1];
    const androidMediaResponse = await fetch(url + androidMediaUrl);
    assert.equal(androidMediaResponse.status, 200);
    assert.equal(androidMediaResponse.headers.get('content-type'), 'image/png');
    assert.ok((await androidMediaResponse.arrayBuffer()).byteLength > 0);
    beforeRejectedImport = await initialCounts();
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
