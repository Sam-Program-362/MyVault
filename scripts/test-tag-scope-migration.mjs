// Isolated PostgreSQL migration regression. Never point this at production.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';

const url = process.env.MIGRATION_TEST_DATABASE_URL;
if (!url) throw new Error('MIGRATION_TEST_DATABASE_URL must point to a disposable PostgreSQL database');
const client = new pg.Client({ connectionString: url });
await client.connect();
try {
  await client.query('BEGIN');
  await client.query('CREATE SCHEMA myvault_tag_scope_test');
  await client.query('SET LOCAL search_path TO myvault_tag_scope_test');
  await client.query('CREATE TABLE spaces (id uuid PRIMARY KEY)');
  await client.query('CREATE TABLE tags (id uuid PRIMARY KEY, name text NOT NULL UNIQUE, reusable boolean NOT NULL)');
  await client.query('CREATE TABLE entry_tags (entry_id uuid NOT NULL, tag_id uuid NOT NULL REFERENCES tags(id))');
  await client.query("INSERT INTO tags VALUES ('00000000-0000-0000-0000-000000000001', 'Ideas', false), ('00000000-0000-0000-0000-000000000002', 'ideas', true)");
  await client.query("INSERT INTO entry_tags VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001')");
  await client.query(await readFile(new URL('../drizzle/0001_tag_scope_foundation.sql', import.meta.url), 'utf8'));
  const { rows } = await client.query('SELECT id, name, reusable, scope, owner_space_id FROM tags ORDER BY name');
  assert.equal(rows.length, 2);
  assert.ok(rows.every((tag) => tag.scope === 'global' && tag.owner_space_id === null));
  assert.equal(rows.find((tag) => tag.name === 'Ideas').reusable, false);
  assert.equal((await client.query('SELECT tag_id FROM entry_tags')).rows[0].tag_id, '00000000-0000-0000-0000-000000000001');
  console.log('PostgreSQL additive tag migration preserves legacy records and links.');
} finally {
  await client.query('ROLLBACK');
  await client.end();
}
