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
same Unicode 15.1 decomposition, combining class, and default full-fold data.
It reads the shared fixture and compares *database-computed* NFD keys. The final
policy specifies NFC: NFD and NFC have the same equality classes for canonically
equivalent strings, but these prototype keys are not the policy's NFC bytes.
It also exercises disposable insert/update triggers, transaction rollback, and two competing SQLite connections.

Known limitations: the trigger rejects a spelling-only change on a legacy
collision even when identity is unchanged; SQLite `length`/`substr` stop at an
embedded NUL while PostgreSQL text rejects NUL; no Room migration test. The default PGlite mode has no PostgreSQL concurrency test; the workflow also runs against a disposable PostgreSQL 16 service with two independent connections.
View evaluation recomputes all keys and has quadratic combining-mark ordering;
no performance or resource bound has been established. This is not safe to ship.

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

`.github/workflows/tag-identity-proof.yml` runs this standalone experiment on
API 24 and 29 and prints the native libraries contained in its proof APK. It does
not run, edit, or suppress the separate API 30 DocumentsUI test.

The separate `PlatformSqlProofTest` also runs the generated SQL view and the
43 pinned expected keys on Android's **platform** SQLite, independently of the
bundled driver. To regenerate its view after editing the template:

```sh
node --input-type=module -e "import {viewSql} from './experiments/tag-name/sql-template.mjs'; import {writeFileSync} from 'node:fs';writeFileSync('experiments/tag-name/sqlite-proof-view.sql',viewSql('sqlite')+';\\n')"
```
