# Tag-scope foundation migration (draft)

`0001_tag_scope_foundation.sql` is an additive PostgreSQL migration. Apply it
only through a reviewed deployment migration process **before** deploying code
that selects the new columns. It is not run automatically and is never applied
to production by CI. CI tests it against an isolated ephemeral PostgreSQL
service in a rolled-back transaction.

Every pre-existing tag remains `global` with no owning space. This does not
change `reusable`, the existing name constraint, tag IDs, or entry-tag links.
Legacy case-variant duplicates are preserved. Local tag creation remains
intentionally disabled: the local-space relationship, deletion policy, scoped
uniqueness, UI and portable backup/import must be completed together before
this migration can support the feature. Do not use these columns to create
local tags in the meantime; existing v1 importers cannot preserve them.

`0002_allow_scoped_tag_names.sql` removes the old global exact-name
constraint, which otherwise prevents a local tag sharing a name with a tag in
another Space. It does not add scope-aware uniqueness enforcement. Scoped tag
creation and editing must remain disabled until safe concurrent uniqueness
and legacy-duplicate handling are implemented. Apply the migrations in order
only through a reviewed deployment procedure.

`0003_tag_scope_guards.sql` installs PostgreSQL triggers that serialize tag
mutations and link checks with a transaction-scoped advisory lock. Legacy
case-variant duplicates remain in place; new conflicting keys and edits that
would strand associations are rejected. The SQLite Room 2→3 migration uses
SQLite's serialized writes and analogous triggers. Neither migration is applied
to production by CI. Cross-platform Unicode case folding and legacy-duplicate
backup import are not yet fully verified.
