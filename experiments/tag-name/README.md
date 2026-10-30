# Isolated tag identity prototypes

These experiments are **not** app code, migrations, or production-ready guards.
They use only in-memory or disposable emulator databases. Do not connect them to
user data.

## SQL

Requires Node 22.13+ and an ephemeral local dependency:

```sh
npm install --no-save --package-lock=false --ignore-scripts @electric-sql/pglite@0.5.13
node --experimental-sqlite experiments/tag-name/sql-proof.mjs
```

The script creates a temporary SQLite database and an in-memory PGlite PostgreSQL database and loads the
same Unicode 15.1 decomposition, canonical composition, combining class, and default full-fold data.
It composes to NFC before case folding, then reads the shared fixture and compares *database-computed* NFD keys. The final
policy specifies NFC: NFD and NFC have the same equality classes for canonically
equivalent strings, but these prototype keys are not the policy's NFC bytes.
It also exercises disposable insert/update triggers, transaction rollback, and two competing SQLite connections.

The isolated SQLite guard in `sqlite-proof-guard.sql` first computes the new row's identity into a disposable staging table, then checks a persistent identity cache. Existing duplicates remain in the cache and are reported as groups; identity-preserving spelling edits are permitted, while edits that create a new scoped conflict are rejected. The 10-round SQLite race test uses independent database connections; CI runs the same number of races against PostgreSQL 16. The default PGlite mode cannot prove PostgreSQL concurrency.

**Resource limits are a prototype restriction, not the policy:** the guard rejects names longer than 24 Unicode code points. A 64-code-point exploratory benchmark took 236,073 ms on the API 29 emulator, so it was removed from the routine matrix and the prototype bound lowered. On the latest API 24/29 run, single-pass view timings were: API 24 / SQLite 3.9.2 — 3 code points 148 ms, 18 code points 15,439 ms, 24 code points 80,116 ms, ten typical names 60,975 ms; API 29 / SQLite 3.22.0 — 3 code points 468 ms, 18 code points 3,184 ms, 24 code points 6,320 ms, ten typical names 21,096 ms. Measurements are emulator-dependent and are not stable production benchmarks, but they show unacceptable cost. The artificial 24-code-point bound does not make the implementation suitable for app integration. Reads still expand the recursive view across rows, and migration/backfill cost for real databases is unmeasured. Do not remove or raise the limit without new measurements.

Embedded NUL is rejected directly by the SQLite guard and by a shared prototype persistence-boundary validator before PostgreSQL text binding, because PostgreSQL text cannot represent U+0000. This boundary validation is not production app code. No Room migration proof has yet passed. The SQL result is **not safe to ship**.

### API 24 investigation

The original trigger failed on the exact operation `INSERT INTO n(id,name) VALUES(3,'CAFÉ')`, after seeding IDs 1 and 2 with canonically equivalent `Café` spellings. The API 24 emulator was x86_64 / Android 7.0 and reported SQLite 3.9.2; API 29 reported 3.22.0. The baseline API 24 logcat showed `signal 11 (SIGSEGV), code 128 (SI_KERNEL), fault addr 0x0` in `/system/lib64/libsqlite.so`. A desktop build of SQLite 3.9.1 from the SQLite source bundled in `sqlite3@3.1.6` reproduced the same SQL crash. AddressSanitizer traced it through `sqlite3VdbeSorterClose` → `sqlite3VdbeFreeCursor` → `allocateCursor` → `sqlite3VdbeExec`; the invalid read was through a sorter-cursor pointer. Minimization tied the failure to the conflict trigger re-evaluating the recursive key view, which contains two ORDER BY sorter pipelines, from multiple nested/separate trigger queries. Removing an ORDER BY suppressed the crash only by invalidating general key semantics. The repair stages the computed identity once and compares plain cached rows, rather than repeatedly entering the recursive view from nested trigger queries. A local SQLite 3.9.1 ASAN run of the repaired insert/update cases no longer crashed.

The Android regression test records `sqlite_version()`, runs that exact conflicting insert with the repaired cache guard, and tests legacy edits, NUL, scope and length boundaries. The original defect was reproduced locally under SQLite 3.9.1/AddressSanitizer; the cache-backed trigger then passed the same insert/update cases without a crash. The fixed platform proof passed on API 24 and API 29 in [CI run 41072609542](https://github.com/Sam-Program-362/MyVault/actions/runs/41072609542). A separate API 24/29 Room v1→v3 migration proof has now been added, using a disposable migrated Room database and a mirror table for the experimental SQL; it still needs its own CI validation. Even with the crash removed, the severe measured runtime means pure SQL is not safe to integrate.

`ucd-15.1.json` was generated using `unicodedata2==15.1.0` and
[Unicode 15.1 CaseFolding.txt](https://github.com/unicode-org/unicodetools/blob/main/unicodetools/data/ucd/15.1.0/CaseFolding.txt).
To regenerate, obtain that exact file and run `python3
experiments/tag-name/generate-ucd.py path/to/CaseFolding.txt`. Its SHA-256 is
checked by the generator. Unicode data is distributed under the included license.

## Bundled driver

The separate Gradle project under `driver` uses Requery SQLite 3.49.0 in a
throwaway application, copying the actual current Room data sources at build
time. It attempts to register a value-returning test function in the helper's
`onConfigure`, exercise it during the real Room v1→v3 migration, in a trigger,
and from concurrent pooled queries, and check legacy IDs and links. The test
function is deliberately not a tag normalizer. No MyVault app dependency or
runtime behavior is changed.

Run on a disposable emulator, with Android SDK and Java 17 installed:

```sh
bash experiments/tag-name/driver/run-device.sh
```

The bundled-driver proof remains available with the command above, but it is
not part of the platform SQL CI matrix: its pooled scalar callback test has
intermittent failures and is not a workaround for this investigation.
`.github/workflows/tag-identity-proof.yml` runs the **platform SQL** proof on API
24 and 29 and reports native ABI sizes. It does not run, edit, or suppress the
separate API 30 DocumentsUI test.

`PlatformSqlProofTest` runs the generated SQL view and 51 pinned expected keys on
Android's **platform** SQLite. To regenerate its view after editing the template:

```sh
node --input-type=module -e "import {viewSql} from './experiments/tag-name/sql-template.mjs'; import {writeFileSync} from 'node:fs';writeFileSync('experiments/tag-name/sqlite-proof-view.sql',viewSql('sqlite')+';\\n')"
```
