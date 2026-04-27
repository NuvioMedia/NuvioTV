-- Per-profile AIOMetadata sharing mode.
--
-- Captures whether a non-primary profile mirrors Main's AIOMetadata config in
-- full, just the API keys, or stays fully independent. The Main profile
-- (profile_index = 1) is the source and is always INDEPENDENT.

alter table public.profiles
  add column if not exists aio_sharing text not null default 'INDEPENDENT';

alter table public.profiles
  drop constraint if exists profiles_aio_sharing_check;

alter table public.profiles
  add constraint profiles_aio_sharing_check
    check (aio_sharing in ('FULL_MIRROR', 'KEYS_ONLY', 'INDEPENDENT'));

create or replace function public.sync_push_profiles(p_profiles jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  insert into public.profiles (
    user_id,
    profile_index,
    name,
    avatar_color_hex,
    uses_primary_addons,
    uses_primary_plugins,
    avatar_id,
    is_kids,
    max_age_rating,
    trakt_sharing,
    aio_sharing
  )
  select
    owner_user_id,
    coalesce((item->>'profile_index')::integer, 1),
    coalesce(item->>'name', ''),
    coalesce(item->>'avatar_color_hex', '#1E88E5'),
    coalesce((item->>'uses_primary_addons')::boolean, false),
    coalesce((item->>'uses_primary_plugins')::boolean, false),
    nullif(item->>'avatar_id', ''),
    case
      when coalesce((item->>'profile_index')::integer, 1) = 1 then false
      else coalesce((item->>'is_kids')::boolean, false)
    end,
    case
      when coalesce((item->>'profile_index')::integer, 1) = 1 then null
      when coalesce((item->>'is_kids')::boolean, false) then nullif(item->>'max_age_rating', '')
      else null
    end,
    case
      when coalesce((item->>'profile_index')::integer, 1) = 1 then 'OWN'
      else coalesce(nullif(item->>'trakt_sharing', ''), 'OWN')
    end,
    case
      when coalesce((item->>'profile_index')::integer, 1) = 1 then 'INDEPENDENT'
      else coalesce(nullif(item->>'aio_sharing', ''), 'INDEPENDENT')
    end
  from jsonb_array_elements(coalesce(p_profiles, '[]'::jsonb)) item
  on conflict (user_id, profile_index) do update set
    name = excluded.name,
    avatar_color_hex = excluded.avatar_color_hex,
    uses_primary_addons = excluded.uses_primary_addons,
    uses_primary_plugins = excluded.uses_primary_plugins,
    avatar_id = excluded.avatar_id,
    is_kids = excluded.is_kids,
    max_age_rating = excluded.max_age_rating,
    trakt_sharing = excluded.trakt_sharing,
    aio_sharing = excluded.aio_sharing,
    updated_at = now();
end;
$$;
