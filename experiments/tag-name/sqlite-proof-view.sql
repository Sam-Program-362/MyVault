CREATE VIEW keys AS WITH RECURSIVE
 d0(id,i,s) AS (SELECT id,1,CAST('' AS TEXT) FROM n UNION ALL SELECT d0.id,d0.i+1,d0.s || CASE WHEN unicode(substr(n.name,d0.i,1)) IN (9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288) THEN ' ' ELSE coalesce((SELECT nfd FROM ucd WHERE cp=unicode(substr(n.name,d0.i,1))),substr(n.name,d0.i,1)) END FROM d0 JOIN n ON n.id=d0.id WHERE d0.i<=length(n.name)),
 r0(id,s) AS (SELECT d0.id,d0.s FROM d0 JOIN n ON n.id=d0.id WHERE d0.i>length(n.name)),
 c0(id,p,c,cc) AS (SELECT id,1,substr(r0.s,1,1),coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(r0.s,1,1))),0) FROM r0 WHERE length(s)>0 UNION ALL SELECT c0.id,c0.p+1,substr(r0.s,c0.p+1,1),coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(r0.s,c0.p+1,1))),0) FROM c0 JOIN r0 ON r0.id=c0.id WHERE c0.p<length(r0.s)),
 o0(id,s) AS (SELECT r0.id,(SELECT coalesce(group_concat(c,''),'') FROM (SELECT p.c FROM c0 p WHERE p.id = r0.id ORDER BY (SELECT count(*) FROM c0 z WHERE z.id=p.id AND z.p<=p.p AND z.cc=0),p.cc,p.p)) FROM r0),
 m(id,i,prefix,starter,marks,lastcc) AS (
   SELECT id,1,CAST('' AS TEXT),CAST('' AS TEXT),CAST('' AS TEXT),0 FROM o0
   UNION ALL SELECT m.id,m.i+1,
     CASE WHEN cm.value IS NOT NULL THEN m.prefix WHEN coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(o0.s,m.i,1))),0)=0 THEN m.prefix||m.starter||m.marks ELSE m.prefix END,
     CASE WHEN cm.value IS NOT NULL THEN cm.value WHEN coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(o0.s,m.i,1))),0)=0 THEN substr(o0.s,m.i,1) ELSE m.starter END,
     CASE WHEN cm.value IS NOT NULL THEN m.marks WHEN coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(o0.s,m.i,1))),0)=0 THEN '' ELSE m.marks||substr(o0.s,m.i,1) END,
     CASE WHEN cm.value IS NOT NULL THEN m.lastcc ELSE coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(o0.s,m.i,1))),0) END
   FROM m JOIN o0 ON o0.id=m.id
   LEFT JOIN compositions cm ON cm.a=CASE WHEN length(m.starter)>0 THEN unicode(m.starter) ELSE -1 END
     AND cm.b=unicode(substr(o0.s,m.i,1)) AND (m.lastcc=0 OR m.lastcc<coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(o0.s,m.i,1))),0))
   WHERE m.i<=length(o0.s)),
 nfc(id,s) AS (SELECT m.id,m.prefix||m.starter||m.marks FROM m JOIN o0 ON o0.id=m.id WHERE m.i>length(o0.s)),
 d1(id,i,s) AS (SELECT id,1,CAST('' AS TEXT) FROM nfc UNION ALL SELECT d1.id,d1.i+1,d1.s || coalesce((SELECT fold FROM ucd WHERE cp=unicode(substr(nfc.s,d1.i,1))),substr(nfc.s,d1.i,1)) FROM d1 JOIN nfc ON nfc.id=d1.id WHERE d1.i<=length(nfc.s)),
 r1(id,s) AS (SELECT d1.id,d1.s FROM d1 JOIN nfc ON nfc.id=d1.id WHERE d1.i>length(nfc.s)),
 c1(id,p,c,cc) AS (SELECT id,1,substr(r1.s,1,1),coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(r1.s,1,1))),0) FROM r1 WHERE length(s)>0 UNION ALL SELECT c1.id,c1.p+1,substr(r1.s,c1.p+1,1),coalesce((SELECT cc FROM ucd WHERE cp=unicode(substr(r1.s,c1.p+1,1))),0) FROM c1 JOIN r1 ON r1.id=c1.id WHERE c1.p<length(r1.s)),
 o1(id,s) AS (SELECT r1.id,(SELECT coalesce(group_concat(c,''),'') FROM (SELECT p.c FROM c1 p WHERE p.id = r1.id ORDER BY (SELECT count(*) FROM c1 z WHERE z.id=p.id AND z.p<=p.p AND z.cc=0),p.cc,p.p)) FROM r1),
 spaces(id,i,s) AS (SELECT id,1,CAST('' AS TEXT) FROM o1 UNION ALL SELECT spaces.id,spaces.i+1,CASE WHEN substr(o1.s,spaces.i,1)=' ' AND (length(spaces.s)=0 OR substr(spaces.s,length(spaces.s),1)=' ') THEN spaces.s ELSE spaces.s || substr(o1.s,spaces.i,1) END FROM spaces JOIN o1 ON o1.id=spaces.id WHERE spaces.i<=length(o1.s))
 SELECT spaces.id,rtrim(spaces.s,' ') COLLATE BINARY AS identity FROM spaces JOIN o1 ON o1.id=spaces.id WHERE spaces.i>length(o1.s);
