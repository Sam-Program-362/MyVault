# Local/Global tag-scope foundation (draft; not production-ready)

The baseline web schema has an exact `UNIQUE(tags.name)` constraint and no
tracked Drizzle migration history. Android Room is schema version 1 with no
registered migrations. The SQL under this directory is the proposed ordered
history for review and isolated tests; none of it is deployed automatically or
applied to production.

`0001_tag_scope_foundation.sql` adds nullable `owner_space_id` and a `scope`
column defaulting to `global`. Existing IDs, names, `reusable` values and
`entry_tags` rows remain untouched. `reusable` is independent of scope.

`0002_allow_scoped_tag_names.sql` is deliberately a no-op barrier: it does NOT
drop the baseline exact-name constraint. Removing that constraint before a
Unicode 15.1 identity key is enforced in both database engines would allow
conflicting writes. The retained exact-name constraint also means same-named
Local tags cannot yet be created in PostgreSQL.

`0003_tag_scope_guards.sql` fails closed: PostgreSQL rejects every new tag and
name/scope/owner mutation. Metadata edits remain available. Association guards
reject Local-tag links from another Space. Room v1→v3 migrations mirror this
policy. Scope-aware selection, filtering, management labels, and version-2
backup serialization are implemented, but importing unknown tag identities is
also rejected before data changes. A v1/v2 backup can only reuse tag IDs and
definitions already present; cross-platform import that would need name-based
remapping remains blocked.

The exact agreed identity policy is not enforced here. `lower()` is not used as
a uniqueness substitute; SQLite lowercases ASCII only, and PostgreSQL's locale
and Unicode data are not guaranteed to match the pinned Unicode 15.1 policy.
See `docs/tag-name-policy.md` for the blocker and options. Do not claim scoped
uniqueness, enable tag creation, remove the old constraint, or deploy these
migrations to production until that blocker is resolved.

## Testing and rollback

CI applies these files only to an ephemeral PostgreSQL schema with legacy rows,
then verifies preservation and fail-closed writes. Room tests upgrade disposable
v1 and v2 databases. No CI job uses a production URL.

PostgreSQL rollback order is: remove the 0003 triggers/functions, treat 0002 as
no-op, then run `rollback/0001_tag_scope_foundation.sql`. The column rollback
refuses to proceed if any non-global scope or owner exists and does not touch
legacy tag rows or links. Room downgrade is intentionally unsupported: SQLite
columns are retained rather than rebuilding a user database destructively.
