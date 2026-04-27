-- AIOMetadata becomes per-profile so Kids profiles can run their own
-- upstream config (different catalog filters) without touching Main.
--
-- Old shape: one row per user (user_id PK).
-- New shape: one row per (user_id, profile_id) pair, with profile_id=1
-- being the existing Main row.

alter table public.aio_metadata_links
  add column if not exists profile_id integer not null default 1
    check (profile_id >= 1);

-- Drop the old user_id PK and (user_id, aio_uuid) uniqueness becomes implicit
-- via the new composite PK. aio_uuid still has its own unique constraint
-- because upstream UUIDs are globally distinct.
alter table public.aio_metadata_links
  drop constraint if exists aio_metadata_links_pkey;

alter table public.aio_metadata_links
  add constraint aio_metadata_links_pkey
    primary key (user_id, profile_id);

-- When a non-primary profile is deleted, also drop its AIO link so the row
-- doesn't linger. Mirrors the cascade pattern used by other profile-scoped
-- tables.
create index if not exists idx_aio_metadata_links_user_profile
  on public.aio_metadata_links (user_id, profile_id);

-- Extend the per-profile cleanup RPC so deleting a profile also removes
-- its AIO link row. Primary profile (id=1) keeps its link the same way
-- the function preserves the primary row for other profile-scoped tables.
create or replace function public.sync_delete_profile_data(p_profile_id integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  delete from public.addons where user_id = owner_user_id and profile_id = p_profile_id;
  delete from public.plugins where user_id = owner_user_id and profile_id = p_profile_id;
  delete from public.watch_progress where user_id = owner_user_id and profile_id = p_profile_id;
  delete from public.library_items where user_id = owner_user_id and profile_id = p_profile_id;
  delete from public.watched_items where user_id = owner_user_id and profile_id = p_profile_id;
  delete from public.profile_settings where user_id = owner_user_id and profile_id = p_profile_id;

  if p_profile_id <> 1 then
    delete from public.aio_metadata_links
       where user_id = owner_user_id and profile_id = p_profile_id;
    delete from public.profiles where user_id = owner_user_id and profile_index = p_profile_id;
  else
    update public.profiles
       set pin_hash = null,
           pin_failed_attempts = 0,
           pin_locked_until = null,
           updated_at = now()
     where user_id = owner_user_id and profile_index = p_profile_id;
  end if;
end;
$$;
