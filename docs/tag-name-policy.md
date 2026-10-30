# Tag-name identity: proposed migration, not yet implemented

This proposal is not enforced by the present application or databases; do not
interpret the present scoped-name guards as Unicode-safe. Stock SQLite's
`lower()` folds ASCII only, while Kotlin, JavaScript and PostgreSQL use
separate Unicode versions and algorithms. A shared derived key provided only
by clients is insufficient: direct or concurrent database writes could lie
about it. In particular, a new unique index on that key would also fail or
force loss of existing colliding rows. Neither is acceptable.

## Proposed versioned rule

For comparisons only, use Unicode 15.1 data, locale-independent default full
case folding, canonical (NFC) normalization, and the Unicode 15.1 White_Space
property. Convert each maximal run of White_Space characters to one U+0020,
remove leading/trailing U+0020, normalize to NFC, full-case-fold, and normalize
to NFC again. Reject an empty result for new names. Keep the original display
name verbatim. Diacritics are significant: `café` differs from `cafe`; composed
and decomposed `café` compare equal. `Straße` and `STRASSE` compare equal;
Greek sigma and final sigma compare equal; Cyrillic case variants compare equal.
Do not use locale-specific Turkic casing. Scope is `(global, null)` or
`(local, owner Space ID)`. `reusable` does not participate in identity.

## Safe migration strategy

1. Build one pinned implementation and test vector corpus, used by Android,
   web, and database enforcement. Verify the chosen SQLite integration can
   compute and verify keys on *every connection*, including Room migrations,
   direct SQL writes, background access and concurrent writes; a client-supplied
   unvalidated key is not adequate. PostgreSQL must use the same pinned tables,
   not database-locale `lower()`. Exact packaging for SQLite is still unresolved.
2. In an isolated clone, calculate keys for existing names without changing tag
   IDs, names, ownership, `reusable`, entry links, or Space records. Group by
   scope and computed key; report every collision with all IDs and affected
   entry counts. A Space ID must remain part of each Local namespace.
3. Keep all colliding legacy rows and associations. Preserve display names;
   allow edits that leave their scope/key unchanged, but reject creation or
   movement into an occupied namespace. Surface collision IDs and associations
   so the user can explicitly rename or otherwise resolve individual records;
   never select a winner or automatically merge/delete tags.
4. Add a tracked, transactional migration and guards only after the same
   implementation is verifiably available to both DB engines. Concurrency
   checks and entry attachment/ownership guards must remain serialized. Include
   rollback tests, cross-platform imports, existing duplicates, exact and case
   variants, normalization, whitespace, accented and non-Latin scripts.
5. Verify on isolated PostgreSQL and Room v1→current upgrade fixtures before
   requesting approval to deploy anything. Do not apply migration to production.

The shared fixture includes combining-mark reordering before case folding (Greek
U+0345), supplementary-plane case variants, accent distinctions, locale-neutral
Turkic I, non-Latin names, whitespace, scope, and reusable independence.
Correct canonical ordering is essential: mapping each original code point to its
folded value independently before normalization can turn canonically equivalent
Greek strings into different keys. These vectors are reference cases, not yet
Android, web or database conformance tests.

No schema migration or normalization implementation has been added by this
proposal. The user-visible conflict-resolution interface and cross-engine
conformance tests remain outstanding.
