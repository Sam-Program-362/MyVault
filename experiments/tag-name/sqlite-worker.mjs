// Two workers write a disposable proof database. Not used by the application.
import { DatabaseSync } from 'node:sqlite';
import { parentPort, workerData } from 'node:worker_threads';
const db = new DatabaseSync(workerData.file);
db.exec('PRAGMA busy_timeout=15000');
const gate = new Int32Array(workerData.gate);
parentPort.postMessage('ready');
Atomics.wait(gate,0,0);
try {
  db.exec('BEGIN IMMEDIATE');
  db.prepare('INSERT INTO n(id,name) VALUES(?,?)').run(workerData.id,workerData.name);
  db.exec('COMMIT');
  parentPort.postMessage({ok:true});
} catch (error) {
  try { db.exec('ROLLBACK'); } catch { /* transaction did not start */ }
  parentPort.postMessage({ok:false,message:error.message});
} finally { db.close(); }
