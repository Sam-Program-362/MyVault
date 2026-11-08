-- Deliberate no-op migration barrier.
-- The baseline tags.name UNIQUE constraint remains in place until the exact
-- Unicode 15.1 identity rule can be enforced by both database engines. Dropping
-- it now would permit conflicts; retaining it means scoped duplicates cannot be
-- created yet. Do not remove the constraint in a deployment migration.
DO $$ BEGIN
  RAISE NOTICE 'Scoped tag creation remains disabled pending Unicode 15.1 enforcement';
END $$;
