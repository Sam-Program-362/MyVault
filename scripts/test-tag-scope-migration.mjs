// Isolated PostgreSQL migration regression. Never point this at production.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';

const url = process.env.MIGRATION_TEST_DATABASE_URL;
if (!url) throw new Error('MIGRATION_TEST_DATABASE_URL must point to a disposable PostgreSQL database');
const client = new pg.Client({ connectionString: url });
const migration = (name) => readFile(new URL(`../drizzle/${name}`, import.meta.url), 'utf8');
async function expectRejected(run, pattern) {
  await client.query('SAVEPOINT expected_rejection');
  await assert.rejects(run, pattern);
  await client.query('ROLLBACK TO SAVEPOINT expected_rejection');
  await client.query('RELEASE SAVEPOINT expected_rejection');
}
await client.connect();
try {
  await client.query('BEGIN');
  await client.query('CREATE SCHEMA myvault_tag_scope_test');
  await client.query('SET LOCAL search_path TO myvault_tag_scope_test');
  await client.query('CREATE TABLE spaces (id uuid PRIMARY KEY)');
  await client.query("CREATE TABLE tags (id uuid PRIMARY KEY, name text NOT NULL CONSTRAINT tags_name_unique UNIQUE, reusable boolean NOT NULL, color text NOT NULL DEFAULT '#6366f1', text_color text NOT NULL DEFAULT '#ffffff')");
  await client.query('CREATE TABLE entries (id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES spaces(id))');
  await client.query('CREATE TABLE entry_tags (entry_id uuid NOT NULL REFERENCES entries(id), tag_id uuid NOT NULL REFERENCES tags(id), PRIMARY KEY(entry_id, tag_id))');
  await client.query("INSERT INTO spaces VALUES ('00000000-0000-0000-0000-0000000000a1'), ('00000000-0000-0000-0000-0000000000b1')");
  await client.query("INSERT INTO entries VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000a1'), ('00000000-0000-0000-0000-0000000000e2', '00000000-0000-0000-0000-0000000000b1')");
  await client.query("INSERT INTO tags(id, name, reusable) VALUES ('00000000-0000-0000-0000-000000000001', 'Ideas', false), ('00000000-0000-0000-0000-000000000002', 'ideas', true), ('00000000-0000-0000-0000-000000000003', 'Local legacy', false)");
  await client.query("INSERT INTO entry_tags VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-000000000001'), ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-000000000003')");
  await client.query(await migration('0001_tag_scope_foundation.sql'));
  await client.query("UPDATE tags SET scope='local', owner_space_id='00000000-0000-0000-0000-0000000000a1' WHERE id='00000000-0000-0000-0000-000000000003'");
  await client.query(await migration('0002_allow_scoped_tag_names.sql'));

  // Same-named Local tags are not enabled: baseline exact-name constraint remains.
  await expectRejected(() => client.query("INSERT INTO tags(id,name,reusable,scope,owner_space_id) VALUES ('00000000-0000-0000-0000-000000000004','Local legacy',true,'local','00000000-0000-0000-0000-0000000000b1')"));
  await client.query(await migration('0003_tag_scope_guards.sql'));

  const { rows } = await client.query('SELECT id, name, reusable, scope, owner_space_id FROM tags ORDER BY id');
  assert.equal(rows.length, 3);
  assert.deepEqual(rows.map(({ id }) => id), [
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000003',
  ]);
  assert.deepEqual(rows.filter((row) => row.id.endsWith('0001') || row.id.endsWith('0002')).map((row) => row.scope), ['global', 'global']);
  assert.equal(rows[0].reusable, false);
  assert.equal(rows[2].owner_space_id, '00000000-0000-0000-0000-0000000000a1');
  assert.deepEqual((await client.query('SELECT entry_id, tag_id FROM entry_tags ORDER BY tag_id')).rows, [
    { entry_id: '00000000-0000-0000-0000-0000000000e1', tag_id: '00000000-0000-0000-0000-000000000001' },
    { entry_id: '00000000-0000-0000-0000-0000000000e1', tag_id: '00000000-0000-0000-0000-000000000003' },
  ]);

  await client.query("UPDATE tags SET color = '#123456', reusable = true WHERE id='00000000-0000-0000-0000-000000000003'");
  await expectRejected(() => client.query("INSERT INTO tags(id,name,reusable) VALUES ('00000000-0000-0000-0000-000000000005','New tag',true)"), /Unicode 15.1/);
  await expectRejected(() => client.query("UPDATE tags SET name='Changed' WHERE id='00000000-0000-0000-0000-000000000003'"), /name and scope changes are paused/);
  await expectRejected(() => client.query("INSERT INTO entry_tags VALUES ('00000000-0000-0000-0000-0000000000e2','00000000-0000-0000-0000-000000000003')"), /another Space/);
  await expectRejected(() => client.query("UPDATE entries SET space_id='00000000-0000-0000-0000-0000000000b1' WHERE id='00000000-0000-0000-0000-0000000000e1'"), /Local tag/);
  assert.equal((await client.query('SELECT count(*) FROM tags')).rows[0].count, '3');
  console.log('PostgreSQL scope migrations preserve legacy identities/links and fail closed on tag identity writes.');
} finally {
  await client.query('ROLLBACK');
  await client.end();
}
