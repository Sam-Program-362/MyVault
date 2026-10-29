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
