# Tag-name identity blocker: Unicode 15.1

The agreed Unicode 15.1 comparison rule is **not enforced by the application or
by either database**. This branch adds scope metadata and a migration, but all
new tag inserts and name/scope/owner changes now fail closed. Do not interpret
scope visibility, application checks, or the legacy exact-name PostgreSQL
constraint as Unicode-safe scoped uniqueness. Existing tag rows are not
normalized or rewritten.

Stock SQLite `lower()` folds ASCII only. PostgreSQL's locale and Unicode tables
are not guaranteed to match the agreed rule. A key calculated only in Kotlin or
JavaScript is client-only and can be bypassed by direct/concurrent DB writes; it
is not sufficient. The isolated SQL prototype is far too slow for integration.

## Agreed comparison rule

For comparisons only: use Unicode 15.1 data, locale-independent default full
case folding, canonical (NFC) normalization, and the Unicode 15.1 White_Space
property. Convert each maximal run of White_Space characters to one U+0020,
remove leading/trailing U+0020, normalize to NFC, full-case-fold, and normalize
to NFC again. Reject an empty result for new names. Keep the original display
name verbatim. Diacritics are significant: `café` differs from `cafe`; composed
and decomposed `café` compare equal. `Straße` and `STRASSE` compare equal;
Greek sigma and final sigma compare equal; Cyrillic case variants compare
equal. Do not use locale-specific Turkic casing. Scope is `(global, null)` or
`(local, owner Space ID)`. `reusable` does not participate in identity.

## Current safe behavior

- Room v1→v3 and PostgreSQL scope columns preserve legacy IDs, display names,
  `reusable`, and every entry-tag relationship. Legacy tags default Global.
- Database triggers reject all tag inserts and identity mutations; color and
  reusable metadata edits remain available. Existing duplicate groups remain
  untouched. No automatic merge, winner selection, rename, or deletion occurs.
- Space-aware suggestions, filters, displays, and attachment validation honor
  scope when such rows exist. `reusable` remains independent.
- Version-2 exports include scope. Imports that would create or remap tag
  identities fail before committing; cross-platform tag import is incomplete.
- PostgreSQL retains the old exact-name constraint. It is intentionally not
  dropped until a correct replacement is available.

## Decision required to unblock writes

1. **Recommended:** keep the fail-closed behavior until one pinned identity
   implementation is verifiably callable and enforced from every Android SQLite
   connection and every PostgreSQL write path, including migrations/imports and
   direct SQL. This likely requires a packaged SQLite function plus matching
   PostgreSQL function/extension or another database-verifiable design, with
   performance and concurrency proofs before enabling writes.
2. **Explicit policy change:** approve engine-native identity semantics or a
   restricted name repertoire. This can be faster, but it changes the agreed
   Unicode 15.1 behavior and must be an explicit product decision, never a
   silent fallback.
3. **Keep the current boundary:** ship scope metadata, safe reads/filtering and
   identity-preserving management only; leave create/name/scope edits and
   cross-platform tag import disabled.

No further research is underway in this branch. The next step is a product
choice between these bounded options, not another prototype cycle.

## Why the first SQL prototype was rejected

The experimental SQL view ran for tens of seconds per common write on API 24;
its experiment-only 24-code-point cap does not make it suitable for production.
Do not integrate it, use a client-only key, or use the intermittent Requery
scalar-function workaround.

## Migration safety and future acceptance tests

Before enabling writes, pin the implementation and test the shared Unicode 15.1
vectors (including combining-mark reordering, supplementary-plane case pairs,
accent distinctions, locale-neutral Turkic I, non-Latin scripts, whitespace,
scope and reusable independence) through the actual Android and PostgreSQL DB
write APIs. In isolated legacy databases, report every identity collision with
all IDs and linked-entry counts; preserve each row/link and require explicit
resolution. Verify API 24/29-or-supported versions, desktop SQLite, PostgreSQL,
concurrent writers, v1/v2 import, and Room/Drizzle migrations. These finite
vectors are necessary evidence, not an exhaustive Unicode proof.
