-- Remove the fail-closed scope guards before considering a 0001 rollback.
DROP TRIGGER IF EXISTS myvault_tag_guard ON tags;
DROP TRIGGER IF EXISTS myvault_tag_link_guard ON entry_tags;
DROP TRIGGER IF EXISTS myvault_entry_space_guard ON entries;
DROP FUNCTION IF EXISTS myvault_guard_tag();
DROP FUNCTION IF EXISTS myvault_guard_tag_link();
DROP FUNCTION IF EXISTS myvault_guard_entry_space();
