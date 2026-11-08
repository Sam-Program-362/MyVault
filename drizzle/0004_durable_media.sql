-- Durable image bytes live in PostgreSQL rather than Vercel's ephemeral function filesystem.
-- The route enforces the same 2 MiB image bound and validates bytes before insertion.
CREATE TABLE media_assets (
  id uuid PRIMARY KEY,
  mime_type text NOT NULL CHECK (mime_type IN ('image/png', 'image/jpeg', 'image/gif', 'image/webp')),
  byte_size integer NOT NULL CHECK (byte_size BETWEEN 1 AND 2097152),
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  image_bytes bytea NOT NULL,
  staging_token uuid,
  created_at timestamp NOT NULL DEFAULT now(),
  CHECK (octet_length(image_bytes) = byte_size)
);
CREATE INDEX media_assets_staging_token_idx ON media_assets(staging_token) WHERE staging_token IS NOT NULL;
CREATE INDEX media_assets_created_at_idx ON media_assets(created_at);
