// Isolated PostgreSQL-compatible table-driven architecture probe.
// Uses PGlite for disposable SQL only; not a Neon deployment or concurrency proof.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { PGlite } from '@electric-sql/pglite';
const ucd = JSON.parse(readFileSync(new URL('./ucd-15.1.json', import.meta.url)));
const fixture = JSON.parse(readFileSync(new URL('../../tests/fixtures/tag-name-policy-vectors.json', import.meta.url)));
const expectedNfc = JSON.parse(readFileSync(new URL('./expected-identity-nfc.json', import.meta.url)));
const inputs = fixture.pairs.flatMap(p => [p.left,p.right]).concat(fixture.scopedCases.flatMap(c => c.tags.map(t => t.name)), ['', ' \u00a0\t', 'Café']);
const pg = await PGlite.create();
try {
 await pg.exec(`CREATE TABLE ucd(cp integer PRIMARY KEY,nfd text,fold text,cc integer NOT NULL);
 CREATE TABLE compositions(a integer,b integer,value text NOT NULL,PRIMARY KEY(a,b));`);
 await pg.query('INSERT INTO ucd SELECT cp,nfd,fold,cc FROM json_to_recordset($1::json) AS x(cp int,nfd text,fold text,cc int)', [JSON.stringify(ucd.rows.map(([cp,nfd,fold,cc])=>({cp,nfd,fold,cc})))]);
 await pg.query('INSERT INTO compositions SELECT a,b,value FROM json_to_recordset($1::json) AS x(a int,b int,value text)', [JSON.stringify(ucd.compositions.map(([a,b,value])=>({a,b,value})))]);
 await pg.exec(`CREATE FUNCTION mv_nfc_15_1(src text) RETURNS text LANGUAGE plpgsql AS $$
 DECLARE raw integer[] := ARRAY[]::integer[]; ordered integer[]; outc integer[] := ARRAY[]::integer[];
         ch text; mapping text; vcp integer; vcls integer; last_cls integer := 0; starter_pos integer := 0;
         starter integer := 0; composite integer; i integer; bucket integer; pos integer;
 BEGIN
   FOR i IN 1..char_length(src) LOOP
     ch := substr(src,i,1); vcp := ascii(ch); mapping := NULL;
     SELECT nfd INTO mapping FROM ucd WHERE ucd.cp=vcp;
     IF mapping IS NULL THEN raw := array_append(raw,vcp);
     ELSE FOR pos IN 1..char_length(mapping) LOOP raw := array_append(raw,ascii(substr(mapping,pos,1))); END LOOP;
     END IF;
   END LOOP;
   SELECT coalesce(array_agg(q.cp ORDER BY q.grp,q.cls,q.ord),ARRAY[]::integer[]) INTO ordered FROM (
     SELECT u.cp,u.ord,coalesce(d.cc,0) AS cls,
       sum(CASE WHEN coalesce(d.cc,0)=0 THEN 1 ELSE 0 END) OVER (ORDER BY u.ord ROWS UNBOUNDED PRECEDING) AS grp
     FROM unnest(raw) WITH ORDINALITY AS u(cp,ord) LEFT JOIN ucd d ON d.cp=u.cp
   ) q;
   FOREACH vcp IN ARRAY ordered LOOP
     SELECT coalesce(cc,0) INTO vcls FROM ucd WHERE ucd.cp=vcp;
     IF vcls IS NULL THEN vcls := 0; END IF;
     composite := NULL;
     IF starter_pos>0 AND (last_cls=0 OR last_cls<vcls) THEN
       SELECT ascii(value) INTO composite FROM compositions WHERE a=starter AND b=vcp;
     END IF;
     IF composite IS NOT NULL THEN outc[starter_pos] := composite; starter := composite;
     ELSE
       IF vcls=0 THEN starter_pos := coalesce(array_length(outc,1),0)+1; starter := vcp; END IF;
       outc := array_append(outc,vcp); last_cls := vcls;
     END IF;
   END LOOP;
   RETURN coalesce((SELECT string_agg(chr(v),'') FROM unnest(outc) AS z(v)),'');
 END $$;
 CREATE FUNCTION tag_identity_15_1(src text) RETURNS text LANGUAGE plpgsql AS $$
 DECLARE spaced text := ''; folded text := ''; ch text; mapping text; vcp integer; i integer; in_space boolean := true;
 BEGIN
   FOR i IN 1..char_length(src) LOOP
     ch := substr(src,i,1); vcp := ascii(ch);
     IF vcp IN (9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288) THEN
       IF NOT in_space THEN spaced := spaced || ' '; in_space := true; END IF;
     ELSE spaced := spaced || ch; in_space := false;
     END IF;
   END LOOP;
   IF right(spaced,1)=' ' THEN spaced := left(spaced,char_length(spaced)-1); END IF;
   spaced := mv_nfc_15_1(spaced);
   FOR i IN 1..char_length(spaced) LOOP
     ch := substr(spaced,i,1); vcp := ascii(ch); mapping := NULL;
     SELECT ucd.fold INTO mapping FROM ucd WHERE ucd.cp=vcp;
     IF mapping IS NULL THEN folded := folded || ch; ELSE folded := folded || mapping; END IF;
   END LOOP;
   RETURN mv_nfc_15_1(folded);
 END $$;`);
 const outputs = [];
 for (const s of inputs) outputs.push((await pg.query('SELECT tag_identity_15_1($1) AS k',[s])).rows[0].k);
 for (let i=0;i<inputs.length;i++) {
   assert.equal(outputs[i],expectedNfc[i],`PostgreSQL-compatible Unicode 15.1 NFC key mismatch at ${i}: ${JSON.stringify(inputs[i])}`);
 }
 let i=0;
 for (const p of fixture.pairs) { assert.equal(outputs[i]===outputs[i+1],p.equal,JSON.stringify(p)); i+=2; }
 for (const c of fixture.scopedCases) { const [a,b]=c.tags; assert.equal(outputs[i]===outputs[i+1]&&a.scope===b.scope&&a.owner===b.owner,c.conflict,JSON.stringify(c)); i+=2; }
 await pg.exec(`CREATE TABLE tags(id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,name text NOT NULL,scope text NOT NULL,owner text,reusable boolean NOT NULL,identity text NOT NULL DEFAULT 'caller-forgery',CHECK(identity<>''),CHECK((scope='global' AND owner IS NULL) OR (scope='local' AND owner IS NOT NULL)));
 CREATE FUNCTION tag_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.identity := tag_identity_15_1(NEW.name); IF NEW.identity='' THEN RAISE EXCEPTION 'empty identity'; END IF; RETURN NEW; END $$;
 CREATE TRIGGER tag_identity_guard BEFORE INSERT OR UPDATE OF name,scope,owner,identity ON tags FOR EACH ROW EXECUTE FUNCTION tag_identity_guard();
 CREATE UNIQUE INDEX tags_global_identity_uq ON tags(identity) WHERE scope='global';
 CREATE UNIQUE INDEX tags_local_identity_uq ON tags(owner,identity) WHERE scope='local';
 CREATE TABLE spaces(id text PRIMARY KEY);
 CREATE TABLE entries(id text PRIMARY KEY,space_id text NOT NULL REFERENCES spaces(id));
 CREATE TABLE entry_tags(entry_id text NOT NULL REFERENCES entries(id),tag_id integer NOT NULL REFERENCES tags(id),PRIMARY KEY(entry_id,tag_id));
 CREATE FUNCTION tag_link_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
   IF EXISTS(SELECT 1 FROM tags t JOIN entries e ON e.id=NEW.entry_id WHERE t.id=NEW.tag_id AND t.scope='local' AND t.owner IS DISTINCT FROM e.space_id) THEN RAISE EXCEPTION 'Local tag belongs to another Space'; END IF;
   RETURN NEW; END $$;
 CREATE TRIGGER tag_link_guard BEFORE INSERT OR UPDATE ON entry_tags FOR EACH ROW EXECUTE FUNCTION tag_link_guard();
 CREATE FUNCTION entry_space_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
   IF EXISTS(SELECT 1 FROM entry_tags et JOIN tags t ON t.id=et.tag_id WHERE et.entry_id=NEW.id AND t.scope='local' AND t.owner IS DISTINCT FROM NEW.space_id) THEN RAISE EXCEPTION 'Entry has a Local tag from another Space'; END IF;
   RETURN NEW; END $$;
 CREATE TRIGGER entry_space_guard BEFORE UPDATE OF space_id ON entries FOR EACH ROW EXECUTE FUNCTION entry_space_guard();`);
 const add=async (name,scope,owner,reusable=true)=>pg.query('INSERT INTO tags(name,scope,owner,reusable) VALUES($1,$2,$3,$4)',[name,scope,owner,reusable]);
 await add('Ideas','local','space-a',false); await add('IDEAS','local','space-b',true); await add('Ideas','global',null,false);
 await assert.rejects(add('ideas','local','space-a'),/duplicate key/);
 await assert.rejects(add('ideas','global',null),/duplicate key/);
 await add('Straße','global',null,true); await add('Straße','local','space-a',true);
 const old=(await pg.query('SELECT id,name,identity FROM tags ORDER BY id')).rows;
 await assert.rejects(pg.query("UPDATE tags SET name='STRASSE' WHERE id=1"),/duplicate key/);
 assert.deepEqual((await pg.query('SELECT id,name,identity FROM tags ORDER BY id')).rows,old,'failed rename must roll back');
 await pg.exec("INSERT INTO spaces VALUES('space-a'),('space-b'); INSERT INTO entries VALUES('entry-a','space-a'),('entry-b','space-b')");
 const localA=(await pg.query("SELECT id FROM tags WHERE name='Ideas' AND scope='local' AND owner='space-a'")).rows[0].id;
 const localB=(await pg.query("SELECT id FROM tags WHERE name='IDEAS' AND scope='local' AND owner='space-b'")).rows[0].id;
 const globalId=(await pg.query("SELECT id FROM tags WHERE name='Ideas' AND scope='global'")).rows[0].id;
 await pg.query('INSERT INTO entry_tags VALUES($1,$2)', ['entry-a',localA]); await pg.query('INSERT INTO entry_tags VALUES($1,$2)', ['entry-b',localB]);
 await pg.query('INSERT INTO entry_tags VALUES($1,$2)', ['entry-a',globalId]); await pg.query('INSERT INTO entry_tags VALUES($1,$2)', ['entry-b',globalId]);
 await assert.rejects(pg.query('INSERT INTO entry_tags VALUES($1,$2)', ['entry-b',localA]),/Local tag belongs/);
 await assert.rejects(pg.query("UPDATE entries SET space_id='space-b' WHERE id='entry-a'"),/Entry has a Local tag/);
 assert.equal((await pg.query('SELECT count(*)::int AS c FROM entry_tags')).rows[0].c,4);
 await pg.exec("CREATE TABLE legacy_tags(id integer PRIMARY KEY,name text,scope text,owner text); INSERT INTO legacy_tags VALUES(1,'Café','global',NULL),(2,'Café','global',NULL),(3,'Ideas','local','space-a'),(4,'IDEAS','local','space-a'),(5,'Ideas','local','space-b')");
 const legacy=(await pg.query(`SELECT scope,coalesce(owner,'') owner,tag_identity_15_1(name) identity,string_agg(id::text,',' ORDER BY id) ids,count(*)::int n FROM legacy_tags GROUP BY scope,coalesce(owner,''),tag_identity_15_1(name) HAVING count(*)>1 ORDER BY scope,owner`)).rows;
 assert.deepEqual(legacy.map(g=>g.ids),['1,2','3,4']);
 assert.equal((await pg.query('SELECT count(*)::int AS c FROM legacy_tags')).rows[0].c,5,'legacy duplicate scan must preserve all rows');
 const benchmarkInputs=inputs.concat(['x'.repeat(128),'a'+'́'.repeat(64)]);
 const t0=performance.now();
 for(let n=0;n<2000;n++) await pg.query('SELECT tag_identity_15_1($1)',[benchmarkInputs[n%benchmarkInputs.length]]);
 const elapsed=(performance.now()-t0)/2000;
 const writeStart=performance.now();
 const batch=Array.from({length:500},(_,n)=>({name:`bench-${n}`,scope:'local',owner:'space-benchmark',reusable:n%2===0}));
 await pg.query(`INSERT INTO tags(name,scope,owner,reusable) SELECT name,scope,owner,reusable FROM json_to_recordset($1::json) AS x(name text,scope text,owner text,reusable boolean)`,[JSON.stringify(batch)]);
 const insert500Ms=performance.now()-writeStart;
 const renameStart=performance.now(); await pg.query("UPDATE tags SET name='bench-renamed' WHERE name='bench-0'");
 const renameMs=performance.now()-renameStart;
 const storageBytes=(await pg.query("SELECT pg_total_relation_size('ucd')+pg_total_relation_size('compositions')+pg_total_relation_size('tags') AS bytes")).rows[0].bytes;
 const memory=process.memoryUsage();
 console.log(JSON.stringify({status:'PASS',engine:'PGlite embedded PostgreSQL-compatible',pgVersion:(await pg.query('SHOW server_version')).rows[0].server_version,unicodeData:ucd.unicode,fixturePairs:fixture.pairs.length,scopeCases:fixture.scopedCases.length,fixtureInputs:inputs.length,databaseTriggerAndUnique:'PASS',scopeAndReusable:'PASS',assignmentIsolationAndGlobalAvailability:'PASS',legacyDuplicateReportWithoutRewrite:'PASS',failedRenameAtomic:'PASS',identityQueryRounds:2000,meanIdentityMs:Number(elapsed.toFixed(4)),insert500Ms:Number(insert500Ms.toFixed(3)),renameOneMs:Number(renameMs.toFixed(3)),databaseRelationBytes:storageBytes,rssBytes:memory.rss,heapUsedBytes:memory.heapUsed,note:'PGlite not Neon or managed PostgreSQL; PGlite single-connection run does not prove concurrent race behavior.'},null,2));
} finally { await pg.close(); }
