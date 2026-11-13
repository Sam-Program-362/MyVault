# Unicode 15.1 tag identity architecture investigation

**Status:** research only; no production architecture is approved. **Decision: more research is required.** The previous recursive SQL view remains unsuitable. Two faster database-boundary prototypes pass the current finite fixture in host harnesses, but neither has been verified on Android Room/API 24+ or managed Neon/PostgreSQL with candidate-specific concurrency and migration tests.

The investigation was performed on PR #2 branch `arena/27fab7d8-myvault`, after safely aligning the checkout to its remote head. This report is not a request to change PR status or production behavior.

## 1. Current architecture and requirements

### Independent editions; parity is not synchronization

Android is a native Kotlin/Compose application using Room and the platform SQLite database, offline-first, with a minimum SDK of 24. The Web edition is Next.js with Drizzle and PostgreSQL on Neon. They are independent products with independent databases and data. The requirement is equivalent policy, rules, validation, capabilities and outcomes—not shared storage, accounts, replication, or Android↔Web synchronization. Nothing in this investigation introduces or proposes such synchronization. Manual backup/import/export remains out of scope.

### Observed tag state and scope semantics

- Android `TagEntity` has an ID, display name, `reusable`, scope (`global` by default) and nullable `ownerSpaceId`. Room schema is version 3. `AppDatabase` migrations add scope metadata without changing IDs or entry-tag links. Its current SQL guards reject tag insert and name/scope/owner mutation, while link/entry-space triggers prevent a Local tag being attached outside its owning Space. Metadata-only edits remain available.
- Web's Drizzle `tags` schema currently has exact `UNIQUE(name)`, `reusable`, scope (`global` default) and nullable `owner_space_id`. The Drizzle scope migrations are additive; the current PostgreSQL trigger blocks creation and identity-changing updates. Existing exact-name uniqueness is deliberately retained.
- Both write boundaries currently fail closed rather than claim Unicode uniqueness. No production tag identity key is installed. Existing tags are not rewritten.
- `reusable` is orthogonal to scope. A Global tag is not limited to its creation Space; a Local tag belongs to one Space and cannot be attached from another.
- The shared research fixture currently specifies duplicate identity rules as follows: Global conflicts with Global; Local conflicts with Local only within the same owner Space; same Local identity in different Spaces is permitted; equal Local and Global names coexist; reusable values do not affect identity. That is the proposed research semantics in the fixture—not evidence that currently enabled production writes implement those cases. Because create/rename is blocked, a product owner should explicitly confirm the Local-versus-Global coexistence case before any future write enablement. Do not infer behavior from the old Web exact-name index, which blocks more cases than the proposed scoped rules.

Relevant inspected files include `Entities.kt`, `AppDatabase.kt`, `Daos.kt`, `src/db/schema.ts`, the current `/api/tags` handlers, `drizzle/0001..0003`, `docs/tag-name-policy.md`, the Android migration tests, and `tests/fixtures/tag-name-policy-vectors.json`.

## 2. Exact identity operation and Unicode data

The policy used by this research is unchanged:

1. Trim leading/trailing code points with Unicode 15.1 `White_Space` and collapse each internal run to U+0020.
2. NFC.
3. Locale-independent **default full** case fold (the C and F mappings; exclude Turkic T mappings).
4. NFC again.

The generated whitespace set is the Unicode 15.1 `White_Space` property: U+0009–U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Accents remain significant. Do not substitute lowercase, remove marks, or use compatibility normalization.

The existing research generator uses `unicodedata2==15.1.0` and the official 15.1.0 `CaseFolding.txt`, applying C+F rather than S or T. The fetched official file matched its pinned SHA-256 `4e55acfdc32825a22e87670e9056a3bf94ad7c5400065778e9e10f8314372bcf`. `ucd-15.1.json` contains 15,239 changed-decomposition/fold/combining-class rows and 12,113 canonical composition entries; it is about 694 KB. Its regenerated SHA-256 is recorded in the verification section below. The source license is `UNICODE-LICENSE.txt`.

For database text storage, PostgreSQL cannot represent U+0000. Reject it explicitly at that persistence boundary; this is distinct from weakening the Unicode identity rule for representable strings.

The fixture has 20 equality/distinction pairs and four scoped cases. The earlier `expected-keys.json` stores NFD comparison keys, which establish equality classes but are not the requested NFC bytes. This investigation added `expected-identity-nfc.json`, generated using the pinned Unicode 15.1 Python normalization data plus full casefold data, and updated both architecture harnesses to assert exact NFC output against it. These finite vectors are not an exhaustive Unicode conformance suite; the official full `NormalizationTest.txt` suite has not been run.

Official references:

- [Unicode 15.1 Normalization, UAX #15](https://www.unicode.org/reports/tr15/)
- [Unicode 15.1 CaseFolding.txt](https://www.unicode.org/Public/15.1.0/ucd/CaseFolding.txt)
- [Unicode 15.1 PropList.txt](https://www.unicode.org/Public/15.1.0/ucd/PropList.txt)

## 3. Candidate approaches

### A. Stock Android SQLite with recursive SQL/lookups

This is the previous prototype: generated Unicode data tables plus recursive CTEs perform decomposition, canonical ordering/sorting, composition, folding, and another ordering pass. SQL triggers and cached identity tables can enforce uniqueness after key computation. It is genuinely database-side in the disposable prototype, and the shared expected-key proof passed on Android's platform SQLite on API 24 and 29. However, its measured times are seconds to tens of seconds. It is rejected for interactive production use. Replacing an `ORDER BY` to hide the older API 24 sorter crash changed semantics; that is not acceptable.

### B. Android database-registered scalar function + CHECK/unique index (promising, unverified on Android)

A deterministic `tag_identity_15_1(name)` scalar function can use generated, version-pinned tables/logic and return exact NFC identity. A table can store the identity alongside the display name with a `CHECK(identity = tag_identity_15_1(name))`, a non-empty check, and scope-aware unique indexes. The application may supply a proposed key, but the database recomputes it in the CHECK; a forged key fails. A writer that has no registered function fails closed rather than silently bypassing the check. This distinguishes identity correctness from uniqueness. A unique index provides atomic uniqueness for competing writers; rename is one statement and rolls back if it violates the index.

The prototype schema in `architecture-proof.mjs` exercises this model on Node's SQLite 3.51.3 with the UDF registered. It is **not** an Android result. Its CHECK proves a wrong caller-supplied key is rejected for ordinary DML on a trusted connection; the harness does not prove protection against a connection that replaces the same-named UDF or executes `PRAGMA ignore_check_constraints=ON`. SQLite has no SQL roles/GRANT boundary. A production Android driver would need to control every connection and deny bypassing PRAGMAs/schema alteration (for example with a native authorizer), or otherwise provide a non-bypassable generated-key/write interface, with explicit tests. This is an additional unresolved enforcement requirement, not something the prototype has solved. The actual Android blocker is function availability/registration on every connection. Android's framework SQLite custom-function hooks are hidden/internal APIs, not a supported Room callback contract for API 24–34 ([framework source](https://android.googlesource.com/platform/frameworks/base/+/baefdfa/core/java/android/database/sqlite/SQLiteDatabase.java)). The documented [`androidx.sqlite.SQLiteConnection`](https://developer.android.com/reference/androidx/sqlite/SQLiteConnection) interface exposes SQL statement preparation, not scalar-function registration. Generated columns do not solve this registration problem and are unavailable to older platform SQLite: [SQLite added generated columns in 3.31.0](https://www.sqlite.org/gencol.html), after the observed API 24 (3.9.2) and API 29 (3.22.0) engines.

A viable Android path likely requires a bundled/custom SQLite engine with a native or supported driver-level callback, installed before every pooled connection is used. This creates real decisions about Room compatibility, every connection path, migration-time registration, native ABI coverage, binary size, SQLite maintenance, and database portability. The existing Requery bundled-driver experiment is not evidence of a production solution: its pooled callback proof is intermittent and is not used in the current platform SQL CI matrix. AndroidX's current SQLite driver API likewise has no public function-registration method. No candidate was tested on API 24, 29, 30 or 34 using Room and the candidate UDF.

### C. Web PostgreSQL stored function/trigger + unique indexes (promising, not deployment-verified)

A server-side `tag_identity_15_1(text)` can implement the transform from pinned Unicode 15.1 data. A `BEFORE INSERT OR UPDATE` trigger can always replace any caller-supplied identity with the value computed from `NEW.name`; the web client cannot choose a key. Partial indexes enforce the rules independently of the trigger: unique identity among Global rows and unique `(owner_space_id, identity)` among Local rows. The trigger, transaction and index make inserts/renames atomic; the unique index is the concurrency arbiter. The lookup data/functions must be owned by a migration role, not mutable by the application role. Normal application SQL writers cannot disable triggers; database superusers/owners can alter schema, which is outside ordinary row-write enforcement.

`postgres-architecture-proof.mjs` implements this as an isolated PL/pgSQL table-driven function, trigger and partial indexes. It passes exact NFC outputs and scope assertions in PGlite 18.6. It does not establish behavior on Neon, managed PostgreSQL 16 used in the prior CI workflow, or actual production Neon versions/settings. It also does not run concurrent transactions, a legacy backfill, entry-link preservation, or a Neon migration.

PostgreSQL feature names do not give the required version guarantee. PostgreSQL [`normalize()`](https://www.postgresql.org/docs/current/functions-string.html) is UTF-8-only and tied to server Unicode data, not a promise of Unicode 15.1. PostgreSQL 18 introduced `casefold()` and its [release notes identify Unicode 16.0 data](https://www.postgresql.org/docs/18/release-18.html); that is not pinned 15.1. ICU collations provide [locale-sensitive comparison/sort behavior](https://www.postgresql.org/docs/current/collation.html), not this exact whitespace → NFC → default full casefold → NFC key. ICU versions and collation configuration can vary, and case/diacritic-insensitive collation may erase distinctions this policy preserves. [Neon supports PostgreSQL/ICU features](https://neon.com/docs/reference/compatibility), but a custom native extension is an operational/provider dependency ([Neon documents custom-built extensions as a separately supported capability](https://neon.com/docs/changelog/2023-09-05)), not something this branch can assume.

Therefore use a database function driven by pinned Unicode 15.1 data, not `lower()`, built-in `casefold()` on an unpinned server, or an ICU collation, if PostgreSQL proceeds. The PL/pgSQL table implementation is semantically promising but its measured PGlite performance and actual Neon behavior need further work.

### D. Native SQLite extension / bundled driver on Android

A C/C++ function registered with `sqlite3_create_function_v2` (or statically registered through an auto-extension) can be fast and used by CHECK constraints/triggers/indexes. The function must embed or load the exact 15.1 data, be installed on every connection, and be callable during all Room migrations and direct writes. Using an extension is a credible route to actual DB enforcement, but does not by itself solve the Room driver lifecycle. It brings NDK toolchain/ABI builds, ABI-specific packaging, native security/maintenance, SQLite version drift or replacement, crash risk, licensing, startup/upgrade behavior and binary-size cost. No Android extension was built here; size, PSS, API compatibility and connection-pool behavior are **Not verified**. The bundled-driver proof previously attempted a callback but is explicitly intermittent, so it cannot be promoted without a stable API 24+ Room proof.

### E. Runtime Android ICU / Java normalizer

`java.text.Normalizer` and Android ICU APIs can normalize strings, but their Unicode data version follows the OS/runtime. Android API 24–34 do not all promise the same Unicode 15.1 data. Java/Android lowercasing is not full case folding (`ß` expansion is a useful counterexample), and locale-root lowercase is still not the specified full default fold. Runtime ICU alone therefore cannot guarantee pinned semantics across the supported range. A frozen 15.1 data table would be needed; the remaining obstacle is database registration/enforcement.

### F. PostgreSQL 18 `casefold`, ICU nondeterministic collations, generated columns, client keys

- PostgreSQL 18 `casefold` plus `normalize` is closer semantically than `lower`, but PG 18's Unicode data is 16.0, not 15.1, and it does not implement the exact White_Space policy. It cannot be used without replacing data/functions with a pinned implementation.
- ICU collations answer sort/equality questions rather than produce the specified canonical NFC key, have version/locale semantics, and can ignore accents or other differences. Not a match.
- A PostgreSQL generated column/functional index is useful only if its function is exact and immutable. A lookup-table-reading function is not safely immutable as the table can change. A trigger that computes a stored key plus protected lookup data and unique index is more defensible.
- A client-only Kotlin/JS key with a unique index fails identity correctness: a direct SQL writer can supply an incorrect key. It is rejected.

## 4. Comparison matrix

| Criterion | Android Room/SQLite | Web Neon/PostgreSQL |
|---|---|---|
| Exact Unicode 15.1 behavior | Host UDF prototype exact NFC passes 51 inputs; API 24+ candidate **Not verified** | PL/pgSQL/PGlite 18.6 exact NFC passes 51 inputs; Neon candidate **Not verified** |
| Full case folding | Frozen C+F table in prototype; framework lower/ICU not pinned | Frozen C+F lookup table in prototype; PG 18 `casefold` is Unicode 16, not accepted |
| NFC normalization | UCD 15.1 composition/decomposition table; exact result host-only | UCD 15.1 table-driven function; exact result in PGlite only |
| White_Space rules | Explicit 25-codepoint table in host prototype | Explicit 25-codepoint table in PGlite prototype |
| Database-enforced key correctness | Node SQLite CHECK recomputation passes ordinary DML; bypass-PRAGMA/UDF control and Room API 24–34 **Not verified** | BEFORE trigger overwrites caller-supplied key in PGlite; Neon role/migration path **Not verified** |
| Database-enforced uniqueness | Unique scope index passes host test; API 24+ transactions/concurrency **Not verified** | Partial unique indexes pass PGlite single-connection tests; competing Neon/PG writers **Not verified** |
| Concurrent write safety | SQLite unique-index design should serialize conflicts; candidate Room race test **Not verified** | PostgreSQL unique-index design is atomic; candidate multi-session test **Not verified** |
| Supported range | Platform SQLite differs; API 24/29 legacy SQL only. Candidate UDF on API 24/29/30/34 **Not verified** | Neon actual server major/config **Not verified**; PGlite harness is 18.6 |
| Performance | Host Node SQLite UDF proxy measured; Android candidate **Not verified**. Recursive SQL rejected | PGlite PL/pgSQL measured; Neon network/server performance **Not verified** |
| Memory/binary/database overhead | SQLite file and host process memory measured; Android native binary/PSS **Not verified** | PGlite relation/process memory measured, not Neon incremental memory |
| Migration safety | Plan exists below; no candidate Room migration/backfill run | Plan exists below; no candidate Neon backfill/migration run |
| Maintenance complexity | High if bundled native driver/UDF; callback/Room lifecycle not resolved | Moderate/high for versioned Unicode tables/functions; database role/deployment/versioning needs testing |

## 5. Benchmark results

### New UDF / table-driven prototypes

Environment: Node v22.22.3. SQLite harness: Node built-in `node:sqlite`, SQLite 3.51.3, temporary database. PostgreSQL-compatible harness: `@electric-sql/pglite@0.5.13`, embedded PostgreSQL 18.6. Unicode tables are the 15.1 generated assets. These are measurements of two **host harnesses**, not Android phones/emulators or Neon. The SQLite function is JavaScript; Android would need an implementation and registration with different costs. The PGlite function is an intentionally straightforward PL/pgSQL/table-lookup implementation. Three fresh process runs were made.

The 51 inputs comprise the current 20 pair vectors, eight names across four scope cases, and three additional inputs (empty, whitespace-only, `Café`). The SQLite repetition sample additionally contains short ASCII, typical accented text, 80 ASCII code points, 64 combining marks, decomposed accents, `ß`, Greek sigma, Turkish dotted I, a supplementary-plane case pair and multiple Unicode whitespace characters. The PostgreSQL-compatible identity loop uses the 51 fixture inputs plus 128 ASCII code points and a 64-mark combining sequence.

| Host harness measurement | SQLite UDF (three runs) | PGlite/PLpgSQL (three runs) |
|---|---:|---:|
| Mean identity SELECT over 20,000 / 2,000 calls | 8.230, 8.339, 8.778 µs/call | 1.7203, 1.7085, 1.7606 ms/call |
| 500 inserts in one transaction | 9.304, 8.019, 7.445 ms | 512.906, 538.977, 539.929 ms |
| One successful rename | 1.299, 1.159, 1.300 ms | 2.483, 2.849, 3.593 ms |
| Stored relation/database size after 500 benchmark rows | SQLite file 90,112 bytes | Unicode + composition + tags relations 2,301,952 bytes |
| Process memory sample | V8 heap 19.2–20.7 MB; RSS 83.6–84.3 MB (not isolated incremental use) | V8 heap 37.8–38.1 MB; RSS 756.3–816.2 MB (PGlite/WASM process total, not attributable incremental use) |

The SQLite three-run median was 8.339 µs per identity call, 8.019 ms for 500 inserts and 1.299 ms for one rename. The PostgreSQL-compatible medians were 1.7203 ms per identity SELECT, 538.977 ms for 500 trigger-backed inserts and 2.849 ms for one rename. These results prove the prototypes run and give reproducible host-level observations; they are not production performance claims. The PGlite 500-row result is a reason to optimize/backfill-test that implementation rather than assume it scales. Relation size is not Neon storage billing. RSS includes the PGlite/WASM runtime and is not a usable memory delta. The SQLite UCD tables are held in the Node process, not stored in the measured SQLite file.

A reasonable **proposed target**, not an approved product promise, is p95 ≤50 ms for interactive tag create/rename from database call start to commit on a representative device/server, excluding network/UI rendering but including identity calculation, checks and unique-index work. That is below a typical 100 ms perceived-interaction threshold. No candidate has been measured against this budget on target Android hardware, production-like PostgreSQL, or over realistic database cardinalities. Do not impose a name-length cap to hit it; benchmark long/pathological strings and choose an explicit input/resource policy only through product review.

### Previous recursive SQL measurements (separate workload; retain as evidence)

The recorded API 24/API 29 numbers in `experiments/tag-name/README.md` are single-pass view timings: API 24 / SQLite 3.9.2: 3 code points 116 ms, 18 code points 7,975 ms, 24 code points 42,499 ms, ten typical names 32,333 ms. API 29 / SQLite 3.22.0: 3 code points 314 ms, 18 code points 1,843 ms, 24 code points 3,708 ms, ten typical names 12,419 ms. A separate API 29 run over 51 inputs reported 48,988 ms for the expected-keys query, max instrumentation-process PSS 421,712 KiB and final 421,842 KiB. That is a different query/workload and must not be compared as if it were an individual-name timing. The historical system-process OOM remains inconclusive; these figures neither explain nor resolve it.

## 6. Unicode conformance and parity results

| Harness | Exact expected NFC outputs | Equality/distinction pairs | Scope / reusable / assignment | Legacy duplicate scan | Rename uniqueness/atomicity | Concurrent creation |
|---|---|---|---|---|---|---|
| Host Node SQLite 3.51.3 UDF + CHECK + unique index | Passed 51 exact outputs against `expected-identity-nfc.json` | 20/20 passed | Four fixture cases and reusable independence passed; Local links blocked across Spaces, Global links accepted in both | Found two groups without changing five seeded rows | Forged key rejected; duplicate/rename rejected; failed row unchanged | **Not verified** for this new harness |
| PGlite 18.6 PL/pgSQL trigger + partial indexes | Passed 51 exact outputs against `expected-identity-nfc.json` | 20/20 passed | Four fixture cases and reusable independence passed; Local links blocked across Spaces, Global links accepted in both | Found two groups without changing five seeded rows | Trigger computes key; duplicate/rename rejected; failed rename leaves row unchanged | **Not verified**; PGlite harness is one connection |
| Android platform SQLite API 24 and 29, prior recursive SQL proof | Prior SQL proof passed its 51 NFD expected keys and guards | Passed in prior CI runs | Passed old prototype scoped assertions; candidate assignment behavior **Not verified** | Prior cache proof reported legacy groups; candidate scan **Not verified** | Passed prior prototype; not the UDF candidate | Prior SQL proof had repeated races in CI; not the new UDF/index schema |
| Neon / managed PostgreSQL candidate | **Not verified** | **Not verified** | **Not verified** | **Not verified** | **Not verified** | **Not verified** |
| Android API 30/34 candidate | **Not verified**; existing app tests are unrelated | **Not verified** | **Not verified** | **Not verified** | **Not verified** | **Not verified** |

Vectors include composed/decomposed and accented distinctions, `ß`/`ss`, sigma variants, Turkic I examples under default folding, multi-code-point folds, canonical mark ordering, Hangul, supplementary-plane case, whitespace collapse, zero-width-format-character distinction, and non-Latin scripts. These vectors are not the Unicode Consortium's full normalization conformance suite. Android's prior proof is valuable evidence that the *old* Unicode SQL view matched expected comparisons on two OS SQLite versions; it does not validate function registration, exact stored NFC bytes, uniqueness implementation or migrations for the proposed UDF candidate.

## 7. Recommended architecture and decision

**No production architecture is established yet.** The best technical direction to investigate next is two separate database-boundary implementations sharing generated Unicode 15.1 conformance assets:

- Android: a supported, pinned SQLite driver/engine with a versioned scalar function registered on every Room connection, and a CHECK-computed identity plus unique partial indexes (or a rigorously guarded trigger-maintained identity column if API 24 semantics/driver support require it). Make missing UDF registration fail closed. Do not use framework ICU or client-only keys.
- Web: a pinned PostgreSQL function/lookup dataset installed by reviewed migration, a trigger that derives/overwrites the key on every insert/update, protected Unicode tables, and separate unique indexes for Global and Local identity. Test against the actual supported Neon major and roles, not only PGlite.

These are directions, not approvals. The Android driver/function lifecycle is unresolved; Neon production parity and concurrency have not been measured; PL/pgSQL bulk backfill is slow in the host harness. A compiled native implementation may improve runtime but increases Android binary and maintenance costs and could be unavailable/unsupported in Neon as a matching extension. The finite host proofs do not satisfy the task's API, concurrency, migration, size and performance acceptance criteria.

**Final choice: More research is required.** Keep current creation/name/scope guards fail-closed. Do not integrate recursive SQL, these host UDF prototypes, runtime platform ICU, PostgreSQL's unpinned casefold, or client-supplied keys into application behavior.

## 8. Migration and preservation plan (not performed)

1. Take a verified backup and preserve the existing migration version. Build disposable copies of representative legacy databases.
2. Install the pinned database function/trigger before enabling any new writes. Verify it from every Room connection and PostgreSQL application role. Add a version marker (for example `unicode_identity_version=15.1`) and prevent application roles from changing lookup assets/functions.
3. In shadow columns/tables, calculate identities without changing display names, IDs, `reusable`, `ownerSpaceId`, or entry-tag rows. Inventory every existing duplicate group by Global identity and Local `(owner Space, identity)`; include every tag ID and linked-entry count in an exportable diagnostic.
4. If any group conflicts, stop before enabling uniqueness or changing scope. Leave all source rows and links intact; do not merge, delete, rename, select a winner, or remap. Require explicit user-directed resolution in a separately designed product flow. A failed scan/backfill must be safely resumable and leave old data usable.
5. Only after a clean report, create the unique indexes in a transaction/maintenance window, validate all keys against the function, test direct SQL and concurrent writers, and record migration completion. Global uniqueness and Local per-Space uniqueness use separate index predicates; Local/Global cross-scope coexistence still requires product confirmation.
6. Test interruption at every boundary. Use transactionally committed migration state and idempotent row batches/shadow progress so restart resumes without duplicate or partial mappings. Keep a backup/restore recovery path. Rollback may remove only new metadata/indexes after confirming no newer client has written rows that depend on them; never revert by deleting or rewriting tags/links.
7. A future Unicode version change must not silently overwrite v15.1 keys. Introduce a parallel vNext key/function, compute and report changed equivalence groups on a copy, resolve collisions explicitly, then atomically switch indexes/version only after product approval. Preserve old key version for rollback until the migration is proven.

No migration or production-data access was performed in this task.

## 9. Remaining uncertainties and experiments required

1. Select and prototype a supported API 24+ Room SQLite engine that supports deterministic scalar function registration per pooled connection and migrations. Build the actual APK, record all ABI `.so` compressed/uncompressed sizes, install/run API 24, 29, 30 and 34, test reopen/upgrade, multi-connection writes, missing-function fail-closed behavior, and attempts to replace the UDF, enable `ignore_check_constraints`, or alter guards. Existing Requery callbacks are not yet reliable; AndroidX driver APIs expose no custom-function registration.
2. Run the candidate function with complete Unicode 15.1 normalization conformance data plus the shared identity vectors. Check exact NFC output, every White_Space code point and all default full-fold expansions; test malformed surrogates/NUL boundaries.
3. Execute candidate SQL on PostgreSQL 16 and the actual supported Neon PostgreSQL major using application credentials/permissions. Verify `server_encoding`, deployment and extension/role assumptions. Measure function, insert, rename, unique conflict and backfill timings with representative existing row counts; record Neon region/compute configuration.
4. Run two-session barrier-controlled same-scope insert/rename races on API 24+ Room and actual PostgreSQL; exactly one conflicting transaction must commit. Test rollback and no partial key-cache/metadata state.
5. Run candidate migrations against disposable copies with clean data, existing duplicate groups, unrelated scopes, interruptions, retry and restore. Verify IDs, owners, reusable flags and all entry-tag links byte-for-byte.
6. Establish p50/p95/p99 for short, long, combining, fold-expansion, whitespace and pathological input groups at realistic row counts. Measure Android PSS, Web query timings, startup, and migration cost; measure database and APK size. Compare to the proposed ≤50 ms p95 interactive database-operation budget without imposing a silent length cap.
7. Confirm product's Local-vs-Global same-name behavior; current fixture says they coexist, but existing Web exact-name uniqueness is stronger and current writes are blocked.
8. Keep the earlier API 29 system-process OOM classified as inconclusive; new root-cause work is not needed for this architecture decision unless new candidate-specific evidence implicates it.

## 10. Changes and verification

### Files changed by this research

- `experiments/tag-name/architecture-proof.mjs` — host Node/SQLite UDF + CHECK and scoped unique-index prototype, exact fixture checks, repeated calls, 500-row insert and rename timings.
- `experiments/tag-name/postgres-architecture-proof.mjs` — disposable PGlite/PLpgSQL UCD-table function + trigger and partial-index prototype, exact fixture checks and timings.
- `experiments/tag-name/expected-identity-nfc.json` — 51 exact NFC expected outputs generated from pinned Unicode 15.1 data.
- `experiments/tag-name/generate-ucd.py` — reproducibly emits the exact NFC expected outputs alongside prior NFD comparison keys.
- `experiments/tag-name/production-architecture-research.md` — this report.
- `experiments/tag-name/README.md` — documents how to run the isolated host probes and links this report.
- `docs/tag-name-policy.md` — points from the earlier “research closed” note to this newer investigation; fail-closed production behavior is unchanged.

No Android/Web application code, production schema, production migrations, production data, backup format, authentication, deployment, release, or synchronization behavior was changed. No database was connected to or migrated. No production deployment was performed.

### Test commands and results

- `python3 -m pip install --target /tmp/unicodedata2-15-1 unicodedata2==15.1.0` — passed. Used only to regenerate the expected fixture.
- `gh api repos/unicode-org/unicodetools/contents/unicodetools/data/ucd/15.1.0/CaseFolding.txt?ref=main` — retrieved official Unicode 15.1 case-fold data; SHA-256 matched the generator's pinned value.
- `PYTHONPATH=/tmp/unicodedata2-15-1 python3 experiments/tag-name/generate-ucd.py /tmp/CaseFolding-15.1.0.txt` — passed; generated 15,239 Unicode rows and exact NFC expected identities.
- `node --experimental-sqlite experiments/tag-name/architecture-proof.mjs` — passed three consecutive runs; all 51 exact output and 20 pair/4 scope fixture assertions passed; DB key CHECK, scope-aware uniqueness, reusable independence, Global cross-Space assignment, Local owner isolation, legacy duplicate scan without row changes, and failed rename atomicity passed.
- `node experiments/tag-name/postgres-architecture-proof.mjs` — passed three consecutive runs; all 51 exact output and 20 pair/4 scope fixture assertions passed; trigger-derived key, partial scope indexes, reusable independence, Global cross-Space assignment, Local owner isolation, legacy duplicate scan without row changes, and failed rename atomicity passed. PGlite is embedded and does not test concurrent sessions.
- Existing CI run [`43362846155`](https://github.com/Sam-Program-362/MyVault/actions/runs/43362846155) — prior recursive SQL proof only: API 24 and 29 driver/Room tests and PostgreSQL/PGlite proof passed; API 29 SQL-free idle observation passed. It does **not** test the candidate UDFs.
- Android candidate/API24/29/30/34, actual Neon candidate, candidate concurrency, migrations, full Unicode NormalizationTest, and native/binary size: **Not verified**.

Data hashes after regeneration:

- `experiments/tag-name/ucd-15.1.json`: `6f7aa0e1a742322ddf303c858d1f35636f553ad8f6df578e5276b02096dfa81a`.
- `experiments/tag-name/expected-identity-nfc.json`: `2ccfe12e54342216d7c941b9f7845119ef53cfc19917dc68b38fc782116099d2`.
- `tests/fixtures/tag-name-policy-vectors.json`: `3a4640a0bb0a53b466b9accc2f43c473d68ab321246167d1b6631c092d5ed408`.
- Official Unicode 15.1 CaseFolding file: `4e55acfdc32825a22e87670e9056a3bf94ad7c5400065778e9e10f8314372bcf`.

## Final recommendation

**More research is required.** Host prototypes show that a fast database-callable function plus a database-side key check/trigger and unique indexes is a credible architectural shape, and the exact NFC fixture passes in both independent host databases. They do not establish Room/API 24–34 function registration, actual Neon behavior, direct/concurrent enforcement under the supported deployment roles, database migration safety, or target-device/server performance. Keep the existing fail-closed production behavior until those platform-specific acceptance gates pass. No synchronization between editions is required or proposed.
