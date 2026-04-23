-- AIOMetadata per-user upstream password
--
-- Upstream (cedya77/aiometadata) now requires a user-chosen password on every
-- save/update/load call — it's the secret that scopes subsequent edits to a
-- given UUID. We mint one per user on first save and keep it alongside the
-- bridge row so a fresh device install can resume config edits without forcing
-- the user to re-mint their UUID.
--
-- No RLS change needed: 005's row-owner policies already cover every column on
-- public.aio_metadata_links.

alter table public.aio_metadata_links
  add column if not exists config_password text;

comment on column public.aio_metadata_links.config_password is
  'Secret used to authenticate subsequent save/update/load calls against the upstream AIOMetadata instance. Never shared cross-user.';
