-- Additive only. Legacy tags keep their IDs, links, and reusable flag.
-- Do not enable scoped tag creation until import/export and uniqueness handling
-- are implemented on both platforms. Apply manually through the deployment's
-- reviewed migration process, never against production in CI.
ALTER TABLE "tags" ADD COLUMN "scope" text NOT NULL DEFAULT 'global';
ALTER TABLE "tags" ADD COLUMN "owner_space_id" uuid REFERENCES "spaces"("id");
