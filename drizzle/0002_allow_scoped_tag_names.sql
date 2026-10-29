-- Preserve all legacy tags and links. Scope-aware uniqueness must be enforced
-- by the application until an index strategy that preserves legacy duplicates
-- is in place. A single global name constraint cannot represent local scopes.
ALTER TABLE "tags" DROP CONSTRAINT IF EXISTS "tags_name_unique";
