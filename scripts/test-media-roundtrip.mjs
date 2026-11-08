// Disposable, two-database media persistence and backup integration test.
// Requires separate *_TEST database URLs; never use production credentials/data.
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { deflateSync } from 'node:zlib';

const sourceUrl = process.env.MIGRATION_TEST_DATABASE_URL;
const destinationUrl = process.env.MEDIA_IMPORT_TEST_DATABASE_URL;
if (!sourceUrl || !destinationUrl) throw new Error('MIGRATION_TEST_DATABASE_URL and MEDIA_IMPORT_TEST_DATABASE_URL are required disposable databases');
const sourceDbName = decodeURIComponent(new URL(sourceUrl).pathname.slice(1));
const destinationDbName = decodeURIComponent(new URL(destinationUrl).pathname.slice(1));
if (sourceDbName === destinationDbName || !/test/i.test(sourceDbName) || !/test/i.test(destinationDbName)) {
  throw new Error('Media round-trip requires two distinct database names containing "test"; refusing unsafe database targets.');
}
const suffix = `${process.pid}_${Math.floor(Math.random() * 1e6)}`;
const sourceSchema = `myvault_media_src_${suffix}`;
const destinationSchema = `myvault_media_dst_${suffix}`;
const sourceClient = new pg.Client({ connectionString: sourceUrl });
const destinationClient = new pg.Client({ connectionString: destinationUrl });
let server = null;

const migration = async (name) => readFile(new URL(`../drizzle/${name}`, import.meta.url), 'utf8');

async function initialize(client, schema) {
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
  await client.query(await migration('0001_tag_scope_foundation.sql'));
  await client.query(await migration('0002_allow_scoped_tag_names.sql'));
  await client.query(await migration('0003_tag_scope_guards.sql'));
  await client.query(await migration('0004_durable_media.sql'));
}

function scopedUrl(value, schema) {
  const url = new URL(value);
  url.searchParams.set('options', `-csearch_path=${schema}`);
  return url.toString();
}

async function waitForApi(url) {
  for (let attempt = 0; attempt < 90; attempt++) {
    try {
      const response = await fetch(`${url}/api/health`);
      if (response.ok) return;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`Next.js did not start at ${url}`);
}

async function startApi(databaseUrl, port) {
  const url = `http://127.0.0.1:${port}`;
  server = spawn('npm', ['run', 'dev', '--', '--hostname', '127.0.0.1', '--port', String(port)], {
    env: { ...process.env, DATABASE_URL: databaseUrl }, stdio: 'inherit', detached: true,
  });
  await waitForApi(url);
  return url;
}

async function stopApi() {
  if (!server?.pid) return;
  try { process.kill(-server.pid, 'SIGTERM'); } catch {}
  await new Promise((resolve) => {
    if (server.exitCode !== null || server.signalCode !== null) resolve();
    else server.once('exit', resolve);
  });
  server = null;
}

function makePng(pixel = [255, 0, 0, 255]) {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let value = n;
    for (let bit = 0; bit < 8; bit++) value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
    return value >>> 0;
  });
  const crc32 = (data) => {
    let crc = 0xffffffff;
    for (const byte of data) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const typeBytes = Buffer.from(type);
    const size = Buffer.alloc(4); size.writeUInt32BE(data.length);
    const checksum = Buffer.alloc(4); checksum.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])));
    return Buffer.concat([size, typeBytes, data, checksum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(1, 0); header.writeUInt32BE(1, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(Buffer.from([0, ...pixel]))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

async function postImage(url, bytes, mime = 'image/png', token) {
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: mime }), 'pixel.png');
  if (token) form.append('stagingToken', token);
  return fetch(`${url}/api/media`, { method: 'POST', body: form });
}

try {
  await sourceClient.connect();
  await destinationClient.connect();
  await initialize(sourceClient, sourceSchema);
  await initialize(destinationClient, destinationSchema);
  await sourceClient.query(
    "INSERT INTO spaces(id,name,sort_order) VALUES ('00000000-0000-4000-8000-000000000001','Media Source',0)",
  );

  let source = await startApi(scopedUrl(sourceUrl, sourceSchema), 3218);
  const png = makePng();
  const upload = await postImage(source, png);
  assert.equal(upload.status, 201, 'valid PNG upload is accepted');
  const stored = await upload.json();
  assert.match(stored.url, /^\/api\/media\/[0-9a-f-]{36}$/i);
  const row = await sourceClient.query('SELECT mime_type, byte_size, image_bytes FROM media_assets WHERE id=$1', [stored.id]);
  assert.equal(row.rowCount, 1);
  assert.equal(row.rows[0].mime_type, 'image/png');
  assert.equal(row.rows[0].byte_size, png.byteLength);
  assert.deepEqual(row.rows[0].image_bytes, png, 'uploaded bytes are stored in PostgreSQL bytea');

  const freshRequest = await fetch(`${source}${stored.url}`);
  assert.equal(freshRequest.status, 200);
  assert.equal(freshRequest.headers.get('content-type'), 'image/png');
  assert.deepEqual(Buffer.from(await freshRequest.arrayBuffer()), png, 'a separate HTTP request reads the durable database bytes');
  const freshDbConnection = new pg.Client({ connectionString: scopedUrl(sourceUrl, sourceSchema) });
  await freshDbConnection.connect();
  const freshRow = await freshDbConnection.query('SELECT image_bytes FROM media_assets WHERE id=$1', [stored.id]);
  assert.deepEqual(freshRow.rows[0].image_bytes, png, 'an independent PostgreSQL connection reads the same bytes');
  await freshDbConnection.end();
  console.log('PASS: valid upload persisted exact bytes and MIME; independent HTTP and PostgreSQL connections read them back.');

  const corrupt = Buffer.from(png); corrupt[42] ^= 1;
  assert.equal((await postImage(source, corrupt)).status, 400, 'bad PNG CRC is rejected');
  assert.equal((await postImage(source, png, 'image/jpeg')).status, 400, 'MIME mismatch is rejected');
  const oversized = await postImage(source, Buffer.alloc(2 * 1024 * 1024 + 1));
  assert.equal(oversized.status, 413, 'image one byte over the per-image limit is rejected');
  assert.equal((await sourceClient.query('SELECT count(*) FROM media_assets')).rows[0].count, '1', 'invalid attempts do not create media rows');

  const entryResponse = await fetch(`${source}/api/entries`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      spaceId: '00000000-0000-4000-8000-000000000001', title: 'Portable media round trip',
      content: `before ![pixel](${stored.url}) after; ![remote](https://example.org/external.png)`,
    }),
  });
  assert.equal(entryResponse.status, 201, await entryResponse.text());
  const createdEntry = await entryResponse.json();
  const exported = await fetch(`${source}/api/export`).then((response) => response.json());
  const exportedEntry = exported.entries.find((entry) => entry.id === createdEntry.id);
  assert.ok(exportedEntry);
  assert.match(exportedEntry.content, /!\[pixel\]\(\/api\/media\//, 'database export contains managed media reference for client-side portability');

  // Exercise the same portable JSON representation produced by makePortableBackup:
  // materialize each durable media reference as validated base64 bytes before import.
  const portableContent = exportedEntry.content.replace(/!\[([^\]]*)\]\((\/api\/media\/[0-9a-f-]{36})\)/gi, (_match, alt, reference) => {
    assert.equal(alt, 'pixel');
    return `![${alt}](data:image/png;base64,${png.toString('base64')})`;
  });
  const portableBackup = {
    version: exported.version,
    spaces: exported.spaces.map((space) => ({ ...space })),
    tags: [],
    entries: [{ ...exportedEntry, content: portableContent, tags: [] }],
  };
  assert.match(portableBackup.entries[0].content, /^before !\[pixel\]\(data:image\/png;base64,/);
  assert.match(portableBackup.entries[0].content, /after; !\[remote\]\(https:\/\/example\.org\/external\.png\)/);
  console.log('PASS: entry export retained the managed reference, and the portable fixture preserved surrounding Markdown and external URLs.');

  await stopApi();
  const destination = await startApi(scopedUrl(destinationUrl, destinationSchema), 3219);
  const importResponse = await fetch(`${destination}/api/import`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(portableBackup),
  });
  assert.equal(importResponse.status, 200, await importResponse.text());
  const restored = await destinationClient.query("SELECT content FROM entries WHERE title='Portable media round trip'");
  assert.equal(restored.rowCount, 1);
  const restoredMatch = /!\[pixel\]\((\/api\/media\/[0-9a-f-]{36})\)/.exec(restored.rows[0].content);
  assert.ok(restoredMatch, 'portable bytes are rewritten to destination managed-media references');
  assert.match(restored.rows[0].content, /after; !\[remote\]\(https:\/\/example\.org\/external\.png\)/);
  const destinationImage = await fetch(`${destination}${restoredMatch[1]}`);
  assert.equal(destinationImage.status, 200);
  assert.equal(destinationImage.headers.get('content-type'), 'image/png');
  assert.deepEqual(Buffer.from(await destinationImage.arrayBuffer()), png, 'two-database backup import preserves the exact image bytes');
  console.log('PASS: portable backup imported into a separate disposable database and rewrote the image to destination media with identical bytes.');

  const stagedToken = '99999999-9999-4999-8999-999999999999';
  const stagedPng = makePng([1, 2, 3, 255]);
  const stagedUpload = await postImage(destination, stagedPng, 'image/png', stagedToken);
  assert.equal(stagedUpload.status, 201);
  const staged = await stagedUpload.json();
  const countsBeforeFailure = await destinationClient.query('SELECT (SELECT count(*) FROM spaces) AS spaces, (SELECT count(*) FROM entries) AS entries, (SELECT count(*) FROM media_assets) AS media');
  const failedImport = await fetch(`${destination}/api/import`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      version: 2, mediaStagingToken: stagedToken,
      spaces: [{ id: 'bad-space', name: 'Must Roll Back' }],
      tags: [{ id: '99999999-9999-4999-8999-999999999998', name: 'Unknown identity' }],
      entries: [{ id: 'bad-entry', spaceId: 'bad-space', content: `![staged](${staged.url})`, tags: [{ id: '99999999-9999-4999-8999-999999999998', name: 'Unknown identity' }] }],
    }),
  });
  assert.equal(failedImport.status, 400, 'unknown tag identity aborts import');
  const countsAfterFailure = await destinationClient.query('SELECT (SELECT count(*) FROM spaces) AS spaces, (SELECT count(*) FROM entries) AS entries, (SELECT count(*) FROM media_assets) AS media');
  assert.deepEqual(countsAfterFailure.rows, countsBeforeFailure.rows, 'failed import commits no spaces, entries, or assets');
  assert.equal((await fetch(`${destination}${staged.url}`)).status, 404, 'staged media remains unavailable after failed import');
  const cleanup = await fetch(`${destination}${staged.url}`, {
    method: 'DELETE', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ stagingToken: stagedToken }),
  });
  assert.equal(cleanup.status, 200);
  assert.equal((await destinationClient.query('SELECT count(*) FROM media_assets')).rows[0].count, '1', 'failed staged bytes can be safely cleaned up');
  console.log('PASS: failed import left entry/media state unchanged, staged bytes stayed inaccessible, and explicit cleanup removed them.');

  // Exercise the real aggregate database quota boundary without uploading 256 MiB
  // over HTTP: per-row CHECK limits are honored while disposable fixture rows fill
  // exactly the configured total, then a distinct valid image must be rejected.
  await stopApi();
  source = await startApi(scopedUrl(sourceUrl, sourceSchema), 3218);
  await sourceClient.query(`
    INSERT INTO media_assets(id,mime_type,byte_size,sha256,image_bytes)
    SELECT gen_random_uuid(), 'image/png', 2097152, lpad(to_hex(n),64,'0'), decode(repeat('00',2097152),'hex')
    FROM generate_series(1,127) AS n
  `);
  const finalQuotaRowBytes = 2 * 1024 * 1024 - png.byteLength;
  await sourceClient.query(
    'INSERT INTO media_assets(id,mime_type,byte_size,sha256,image_bytes) VALUES (gen_random_uuid(), $1, $2, $3, $4)',
    ['image/png', finalQuotaRowBytes, 'f'.repeat(64), Buffer.alloc(finalQuotaRowBytes)],
  );
  assert.equal((await sourceClient.query('SELECT sum(byte_size) FROM media_assets')).rows[0].sum, String(256 * 1024 * 1024));
  const quotaUpload = await postImage(source, makePng([10, 20, 30, 255]));
  assert.equal(quotaUpload.status, 507, 'storage quota rejects a new, valid, distinct image at the limit');
  assert.equal((await sourceClient.query('SELECT count(*) FROM media_assets')).rows[0].count, '129');
  console.log('PASS: exact 256 MiB database quota rejected a distinct valid image with HTTP 507.');

  console.log('PASS: media bytes persisted and fetched from two PostgreSQL connections; export/import across distinct disposable databases preserved bytes and Markdown; invalid, oversized, staged-failure, and quota boundaries verified.');
} catch (error) {
  console.error('MEDIA_ROUNDTRIP_FAILURE', error instanceof Error ? error.stack ?? error.message : String(error));
  throw error;
} finally {
  await stopApi();
  await sourceClient.query(`DROP SCHEMA IF EXISTS ${sourceSchema} CASCADE`).catch(() => undefined);
  await destinationClient.query(`DROP SCHEMA IF EXISTS ${destinationSchema} CASCADE`).catch(() => undefined);
  await sourceClient.end().catch(() => undefined);
  await destinationClient.end().catch(() => undefined);
}
