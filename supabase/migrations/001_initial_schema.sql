create extension if not exists pgcrypto;

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create table if not exists public.profiles (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_index integer not null check (profile_index >= 1),
  name text not null default '',
  avatar_color_hex text not null default '#1E88E5',
  uses_primary_addons boolean not null default false,
  uses_primary_plugins boolean not null default false,
  avatar_id text,
  pin_hash text,
  pin_failed_attempts integer not null default 0,
  pin_locked_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_index)
);

create table if not exists public.profile_settings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id integer not null check (profile_id >= 1),
  settings_json jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id)
);

create table if not exists public.addons (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  url text not null,
  name text,
  enabled boolean not null default true,
  sort_order integer not null default 0,
  profile_id integer not null default 1 check (profile_id >= 1),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, url)
);

create table if not exists public.plugins (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  url text not null,
  name text,
  enabled boolean not null default true,
  sort_order integer not null default 0,
  profile_id integer not null default 1 check (profile_id >= 1),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, url)
);

create table if not exists public.watch_progress (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  content_id text not null,
  content_type text not null,
  video_id text not null,
  season integer,
  episode integer,
  position bigint not null default 0,
  duration bigint not null default 0,
  last_watched bigint not null default 0,
  progress_key text not null,
  profile_id integer not null default 1 check (profile_id >= 1),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, progress_key)
);

create table if not exists public.library_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  content_id text not null,
  content_type text not null,
  name text not null default '',
  poster text,
  poster_shape text not null default 'POSTER',
  background text,
  description text,
  release_info text,
  imdb_rating double precision,
  genres text[] not null default '{}',
  addon_base_url text,
  added_at bigint not null default 0,
  profile_id integer not null default 1 check (profile_id >= 1),
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id, content_id, content_type)
);

create table if not exists public.watched_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  content_id text not null,
  content_type text not null,
  title text not null default '',
  season integer,
  episode integer,
  watched_at bigint not null,
  profile_id integer not null default 1 check (profile_id >= 1),
  updated_at timestamptz not null default now(),
  unique nulls not distinct (user_id, profile_id, content_id, season, episode)
);

create table if not exists public.linked_devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_user_id uuid not null references auth.users(id) on delete cascade,
  device_name text,
  linked_at timestamptz not null default now(),
  unique (owner_id, device_user_id),
  unique (device_user_id)
);

create table if not exists public.sync_codes (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  code text not null unique,
  pin_hash text not null,
  expires_at timestamptz not null,
  claimed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (owner_id)
);

create table if not exists public.avatar_catalog (
  id text primary key,
  display_name text not null,
  storage_path text not null,
  category text not null,
  sort_order integer not null default 0,
  bg_color text
);

create index if not exists idx_profiles_user_profile on public.profiles (user_id, profile_index);
create index if not exists idx_profile_settings_user_profile on public.profile_settings (user_id, profile_id);
create index if not exists idx_addons_user_profile on public.addons (user_id, profile_id, sort_order);
create index if not exists idx_plugins_user_profile on public.plugins (user_id, profile_id, sort_order);
create index if not exists idx_watch_progress_user_profile on public.watch_progress (user_id, profile_id, last_watched desc);
create index if not exists idx_library_items_user_profile on public.library_items (user_id, profile_id, added_at desc);
create index if not exists idx_watched_items_user_profile on public.watched_items (user_id, profile_id, watched_at desc);
create index if not exists idx_linked_devices_owner on public.linked_devices (owner_id);
create index if not exists idx_sync_codes_owner on public.sync_codes (owner_id);

drop trigger if exists trg_profiles_updated_at on public.profiles;
create trigger trg_profiles_updated_at before update on public.profiles
for each row execute function public.set_updated_at();

drop trigger if exists trg_addons_updated_at on public.addons;
create trigger trg_addons_updated_at before update on public.addons
for each row execute function public.set_updated_at();

drop trigger if exists trg_plugins_updated_at on public.plugins;
create trigger trg_plugins_updated_at before update on public.plugins
for each row execute function public.set_updated_at();

drop trigger if exists trg_watch_progress_updated_at on public.watch_progress;
create trigger trg_watch_progress_updated_at before update on public.watch_progress
for each row execute function public.set_updated_at();

drop trigger if exists trg_library_items_updated_at on public.library_items;
create trigger trg_library_items_updated_at before update on public.library_items
for each row execute function public.set_updated_at();

drop trigger if exists trg_watched_items_updated_at on public.watched_items;
create trigger trg_watched_items_updated_at before update on public.watched_items
for each row execute function public.set_updated_at();

create or replace function public.current_sync_owner_id()
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  current_user_id uuid;
  owner_user_id uuid;
begin
  current_user_id := auth.uid();
  if current_user_id is null then
    raise exception 'Not authenticated';
  end if;

  select ld.owner_id
    into owner_user_id
    from public.linked_devices ld
   where ld.device_user_id = current_user_id
   limit 1;

  return coalesce(owner_user_id, current_user_id);
end;
$$;

create or replace function public.can_access_owner(p_owner_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select p_owner_id = auth.uid()
      or exists (
        select 1
          from public.linked_devices ld
         where ld.owner_id = p_owner_id
           and ld.device_user_id = auth.uid()
      );
$$;

alter table public.profiles enable row level security;
alter table public.profile_settings enable row level security;
alter table public.addons enable row level security;
alter table public.plugins enable row level security;
alter table public.watch_progress enable row level security;
alter table public.library_items enable row level security;
alter table public.watched_items enable row level security;
alter table public.linked_devices enable row level security;
alter table public.sync_codes enable row level security;
alter table public.avatar_catalog enable row level security;

drop policy if exists profiles_access on public.profiles;
create policy profiles_access on public.profiles for all
using (public.can_access_owner(user_id))
with check (user_id = public.current_sync_owner_id());

drop policy if exists profile_settings_access on public.profile_settings;
create policy profile_settings_access on public.profile_settings for all
using (public.can_access_owner(user_id))
with check (user_id = public.current_sync_owner_id());

drop policy if exists addons_access on public.addons;
create policy addons_access on public.addons for all
using (public.can_access_owner(user_id))
with check (user_id = public.current_sync_owner_id());

drop policy if exists plugins_access on public.plugins;
create policy plugins_access on public.plugins for all
using (public.can_access_owner(user_id))
with check (user_id = public.current_sync_owner_id());

drop policy if exists watch_progress_access on public.watch_progress;
create policy watch_progress_access on public.watch_progress for all
using (public.can_access_owner(user_id))
with check (user_id = public.current_sync_owner_id());

drop policy if exists library_items_access on public.library_items;
create policy library_items_access on public.library_items for all
using (public.can_access_owner(user_id))
with check (user_id = public.current_sync_owner_id());

drop policy if exists watched_items_access on public.watched_items;
create policy watched_items_access on public.watched_items for all
using (public.can_access_owner(user_id))
with check (user_id = public.current_sync_owner_id());

drop policy if exists linked_devices_owner_access on public.linked_devices;
create policy linked_devices_owner_access on public.linked_devices for all
using (owner_id = auth.uid())
with check (owner_id = auth.uid());

drop policy if exists sync_codes_owner_access on public.sync_codes;
create policy sync_codes_owner_access on public.sync_codes for all
using (owner_id = public.current_sync_owner_id())
with check (owner_id = public.current_sync_owner_id());

drop policy if exists avatar_catalog_read on public.avatar_catalog;
create policy avatar_catalog_read on public.avatar_catalog for select
using (true);

create or replace function public.get_sync_owner()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select public.current_sync_owner_id()::text;
$$;

create or replace function public.generate_sync_code(p_pin text)
returns table(code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
  new_code text;
begin
  if coalesce(length(trim(p_pin)), 0) = 0 then
    raise exception 'Invalid PIN';
  end if;

  owner_user_id := public.current_sync_owner_id();
  new_code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 6));

  insert into public.sync_codes (owner_id, code, pin_hash, expires_at, claimed_at)
  values (owner_user_id, new_code, crypt(p_pin, gen_salt('bf')), now() + interval '10 minutes', null)
  on conflict (owner_id) do update set
    code = excluded.code,
    pin_hash = excluded.pin_hash,
    expires_at = excluded.expires_at,
    claimed_at = null,
    created_at = now();

  return query select new_code;
end;
$$;

create or replace function public.get_sync_code(p_pin text)
returns table(code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  return query
  select sc.code
    from public.sync_codes sc
   where sc.owner_id = owner_user_id
     and sc.claimed_at is null
     and sc.expires_at > now()
     and sc.pin_hash = crypt(p_pin, sc.pin_hash)
   limit 1;

  if not found then
    raise exception 'No sync code found';
  end if;
end;
$$;

create or replace function public.claim_sync_code(
  p_code text,
  p_pin text,
  p_device_name text default null
)
returns table(result_owner_id text, success boolean, message text)
language plpgsql
security definer
set search_path = public
as $$
declare
  current_user_id uuid;
  sync_row public.sync_codes%rowtype;
begin
  current_user_id := auth.uid();
  if current_user_id is null then
    raise exception 'Not authenticated';
  end if;

  select *
    into sync_row
    from public.sync_codes sc
   where sc.code = upper(trim(p_code))
   limit 1;

  if not found then
    return query select null::text, false, 'Sync code not found';
    return;
  end if;

  if sync_row.expires_at <= now() then
    return query select sync_row.owner_id::text, false, 'Sync code has expired';
    return;
  end if;

  if sync_row.claimed_at is not null then
    return query select sync_row.owner_id::text, false, 'Sync code has already been used';
    return;
  end if;

  if sync_row.pin_hash <> crypt(p_pin, sync_row.pin_hash) then
    return query select sync_row.owner_id::text, false, 'Incorrect PIN';
    return;
  end if;

  if sync_row.owner_id = current_user_id then
    return query select sync_row.owner_id::text, false, 'Device is already linked';
    return;
  end if;

  if exists (
    select 1
      from public.linked_devices ld
     where ld.device_user_id = current_user_id
  ) then
    return query select sync_row.owner_id::text, false, 'Device is already linked';
    return;
  end if;

  insert into public.linked_devices (owner_id, device_user_id, device_name)
  values (sync_row.owner_id, current_user_id, nullif(trim(p_device_name), ''));

  update public.sync_codes
     set claimed_at = now()
   where id = sync_row.id;

  return query select sync_row.owner_id::text, true, 'Linked successfully';
end;
$$;

create or replace function public.unlink_device(p_device_user_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := auth.uid();
  if owner_user_id is null then
    raise exception 'Not authenticated';
  end if;

  delete from public.linked_devices
   where owner_id = owner_user_id
     and device_user_id = p_device_user_id::uuid;
end;
$$;

create or replace function public.get_avatar_catalog()
returns setof public.avatar_catalog
language sql
stable
security definer
set search_path = public
as $$
  select *
    from public.avatar_catalog
   order by sort_order asc, display_name asc;
$$;

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
    avatar_id
  )
  select
    owner_user_id,
    coalesce((item->>'profile_index')::integer, 1),
    coalesce(item->>'name', ''),
    coalesce(item->>'avatar_color_hex', '#1E88E5'),
    coalesce((item->>'uses_primary_addons')::boolean, false),
    coalesce((item->>'uses_primary_plugins')::boolean, false),
    nullif(item->>'avatar_id', '')
  from jsonb_array_elements(coalesce(p_profiles, '[]'::jsonb)) item
  on conflict (user_id, profile_index) do update set
    name = excluded.name,
    avatar_color_hex = excluded.avatar_color_hex,
    uses_primary_addons = excluded.uses_primary_addons,
    uses_primary_plugins = excluded.uses_primary_plugins,
    avatar_id = excluded.avatar_id,
    updated_at = now();
end;
$$;

create or replace function public.sync_pull_profiles()
returns setof public.profiles
language sql
stable
security definer
set search_path = public
as $$
  select *
    from public.profiles
   where user_id = public.current_sync_owner_id()
   order by profile_index asc;
$$;

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

create or replace function public.sync_pull_profile_locks()
returns table(profile_index integer, pin_enabled boolean, pin_locked_until timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select
    p.profile_index,
    (p.pin_hash is not null and p.pin_hash <> '') as pin_enabled,
    p.pin_locked_until
  from public.profiles p
  where p.user_id = public.current_sync_owner_id()
  order by p.profile_index asc;
$$;

create or replace function public.set_profile_pin(
  p_profile_id integer,
  p_pin text,
  p_current_pin text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
  existing_hash text;
begin
  if coalesce(length(trim(p_pin)), 0) = 0 then
    raise exception 'Invalid PIN';
  end if;

  owner_user_id := public.current_sync_owner_id();

  insert into public.profiles (user_id, profile_index)
  values (owner_user_id, p_profile_id)
  on conflict (user_id, profile_index) do nothing;

  select pin_hash
    into existing_hash
    from public.profiles
   where user_id = owner_user_id
     and profile_index = p_profile_id;

  if existing_hash is not null and existing_hash <> '' then
    if p_current_pin is null or existing_hash <> crypt(p_current_pin, existing_hash) then
      raise exception 'Incorrect PIN';
    end if;
  end if;

  update public.profiles
     set pin_hash = crypt(p_pin, gen_salt('bf')),
         pin_failed_attempts = 0,
         pin_locked_until = null,
         updated_at = now()
   where user_id = owner_user_id
     and profile_index = p_profile_id;
end;
$$;

create or replace function public.clear_profile_pin(
  p_profile_id integer,
  p_current_pin text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
  existing_hash text;
begin
  owner_user_id := public.current_sync_owner_id();

  select pin_hash
    into existing_hash
    from public.profiles
   where user_id = owner_user_id
     and profile_index = p_profile_id;

  if existing_hash is not null and existing_hash <> '' then
    if p_current_pin is null or existing_hash <> crypt(p_current_pin, existing_hash) then
      raise exception 'Incorrect PIN';
    end if;
  end if;

  update public.profiles
     set pin_hash = null,
         pin_failed_attempts = 0,
         pin_locked_until = null,
         updated_at = now()
   where user_id = owner_user_id
     and profile_index = p_profile_id;
end;
$$;

create or replace function public.verify_profile_pin(
  p_profile_id integer,
  p_pin text
)
returns table(unlocked boolean, retry_after_seconds integer)
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
  profile_row public.profiles%rowtype;
  lock_seconds integer;
begin
  owner_user_id := public.current_sync_owner_id();

  select *
    into profile_row
    from public.profiles
   where user_id = owner_user_id
     and profile_index = p_profile_id;

  if not found or profile_row.pin_hash is null or profile_row.pin_hash = '' then
    return query select true, 0;
    return;
  end if;

  if profile_row.pin_locked_until is not null and profile_row.pin_locked_until > now() then
    return query
    select false, greatest(1, ceil(extract(epoch from (profile_row.pin_locked_until - now())))::integer);
    return;
  end if;

  if profile_row.pin_hash = crypt(p_pin, profile_row.pin_hash) then
    update public.profiles
       set pin_failed_attempts = 0,
           pin_locked_until = null,
           updated_at = now()
     where id = profile_row.id;

    return query select true, 0;
    return;
  end if;

  update public.profiles
     set pin_failed_attempts = pin_failed_attempts + 1,
         pin_locked_until = case when pin_failed_attempts + 1 >= 5 then now() + interval '5 minutes' else null end,
         updated_at = now()
   where id = profile_row.id
   returning greatest(1, ceil(extract(epoch from coalesce(pin_locked_until, now()) - now())))::integer
      into lock_seconds;

  return query select false, coalesce(lock_seconds, 0);
end;
$$;

create or replace function public.sync_push_profile_settings_blob(
  p_profile_id integer,
  p_settings_json jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  insert into public.profile_settings (user_id, profile_id, settings_json, updated_at)
  values (owner_user_id, p_profile_id, coalesce(p_settings_json, '{}'::jsonb), now())
  on conflict (user_id, profile_id) do update set
    settings_json = excluded.settings_json,
    updated_at = now();
end;
$$;

create or replace function public.sync_pull_profile_settings_blob(p_profile_id integer)
returns table(profile_id integer, settings_json jsonb, updated_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select ps.profile_id, ps.settings_json, ps.updated_at
    from public.profile_settings ps
   where ps.user_id = public.current_sync_owner_id()
     and ps.profile_id = p_profile_id
   limit 1;
$$;

create or replace function public.sync_push_addons(
  p_addons jsonb,
  p_profile_id integer
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  delete from public.addons
   where user_id = owner_user_id
     and profile_id = p_profile_id;

  insert into public.addons (user_id, url, sort_order, profile_id)
  select
    owner_user_id,
    item->>'url',
    coalesce((item->>'sort_order')::integer, 0),
    p_profile_id
  from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) item
  where coalesce(item->>'url', '') <> '';
end;
$$;

create or replace function public.sync_push_plugins(
  p_plugins jsonb,
  p_profile_id integer
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  delete from public.plugins
   where user_id = owner_user_id
     and profile_id = p_profile_id;

  insert into public.plugins (user_id, url, name, enabled, sort_order, profile_id)
  select
    owner_user_id,
    item->>'url',
    nullif(item->>'name', ''),
    coalesce((item->>'enabled')::boolean, true),
    coalesce((item->>'sort_order')::integer, 0),
    p_profile_id
  from jsonb_array_elements(coalesce(p_plugins, '[]'::jsonb)) item
  where coalesce(item->>'url', '') <> '';
end;
$$;

create or replace function public.sync_push_watch_progress(
  p_entries jsonb,
  p_profile_id integer
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  insert into public.watch_progress (
    user_id,
    content_id,
    content_type,
    video_id,
    season,
    episode,
    position,
    duration,
    last_watched,
    progress_key,
    profile_id,
    updated_at
  )
  select
    owner_user_id,
    item->>'content_id',
    item->>'content_type',
    item->>'video_id',
    nullif(item->>'season', '')::integer,
    nullif(item->>'episode', '')::integer,
    coalesce((item->>'position')::bigint, 0),
    coalesce((item->>'duration')::bigint, 0),
    coalesce((item->>'last_watched')::bigint, 0),
    item->>'progress_key',
    p_profile_id,
    now()
  from jsonb_array_elements(coalesce(p_entries, '[]'::jsonb)) item
  where coalesce(item->>'progress_key', '') <> ''
  on conflict (user_id, profile_id, progress_key) do update set
    content_id = excluded.content_id,
    content_type = excluded.content_type,
    video_id = excluded.video_id,
    season = excluded.season,
    episode = excluded.episode,
    position = excluded.position,
    duration = excluded.duration,
    last_watched = excluded.last_watched,
    updated_at = now();
end;
$$;

create or replace function public.sync_pull_watch_progress(p_profile_id integer)
returns setof public.watch_progress
language sql
stable
security definer
set search_path = public
as $$
  select *
    from public.watch_progress wp
   where wp.user_id = public.current_sync_owner_id()
     and wp.profile_id = p_profile_id
   order by wp.last_watched desc, wp.updated_at desc;
$$;

create or replace function public.sync_delete_watch_progress(
  p_keys jsonb,
  p_profile_id integer
)
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.watch_progress wp
   where wp.user_id = public.current_sync_owner_id()
     and wp.profile_id = p_profile_id
     and wp.progress_key in (
       select jsonb_array_elements_text(coalesce(p_keys, '[]'::jsonb))
     );
$$;

create or replace function public.sync_push_library(
  p_items jsonb,
  p_profile_id integer
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  delete from public.library_items
   where user_id = owner_user_id
     and profile_id = p_profile_id;

  insert into public.library_items (
    user_id,
    content_id,
    content_type,
    name,
    poster,
    poster_shape,
    background,
    description,
    release_info,
    imdb_rating,
    genres,
    addon_base_url,
    added_at,
    profile_id,
    updated_at
  )
  select
    owner_user_id,
    item->>'content_id',
    item->>'content_type',
    coalesce(item->>'name', ''),
    nullif(item->>'poster', ''),
    coalesce(item->>'poster_shape', 'POSTER'),
    nullif(item->>'background', ''),
    nullif(item->>'description', ''),
    nullif(item->>'release_info', ''),
    nullif(item->>'imdb_rating', '')::double precision,
    coalesce(array(select jsonb_array_elements_text(coalesce(item->'genres', '[]'::jsonb))), '{}'),
    nullif(item->>'addon_base_url', ''),
    coalesce((item->>'added_at')::bigint, 0),
    p_profile_id,
    now()
  from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) item
  where coalesce(item->>'content_id', '') <> '';
end;
$$;

create or replace function public.sync_pull_library(
  p_profile_id integer,
  p_limit integer,
  p_offset integer
)
returns setof public.library_items
language sql
stable
security definer
set search_path = public
as $$
  select *
    from public.library_items li
   where li.user_id = public.current_sync_owner_id()
     and li.profile_id = p_profile_id
   order by li.added_at desc, li.updated_at desc
   limit greatest(coalesce(p_limit, 500), 1)
  offset greatest(coalesce(p_offset, 0), 0);
$$;

create or replace function public.sync_push_watched_items(
  p_items jsonb,
  p_profile_id integer
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
begin
  owner_user_id := public.current_sync_owner_id();

  insert into public.watched_items (
    user_id,
    content_id,
    content_type,
    title,
    season,
    episode,
    watched_at,
    profile_id,
    updated_at
  )
  select
    owner_user_id,
    item->>'content_id',
    item->>'content_type',
    coalesce(item->>'title', ''),
    nullif(item->>'season', '')::integer,
    nullif(item->>'episode', '')::integer,
    coalesce((item->>'watched_at')::bigint, 0),
    p_profile_id,
    now()
  from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) item
  where coalesce(item->>'content_id', '') <> ''
  on conflict (user_id, profile_id, content_id, season, episode) do update set
    content_type = excluded.content_type,
    title = excluded.title,
    watched_at = excluded.watched_at,
    updated_at = now();
end;
$$;

create or replace function public.sync_pull_watched_items(
  p_profile_id integer,
  p_page integer,
  p_page_size integer
)
returns setof public.watched_items
language sql
stable
security definer
set search_path = public
as $$
  select *
    from public.watched_items wi
   where wi.user_id = public.current_sync_owner_id()
     and wi.profile_id = p_profile_id
   order by wi.watched_at desc, wi.updated_at desc
   limit greatest(coalesce(p_page_size, 900), 1)
  offset greatest(coalesce(p_page, 1) - 1, 0) * greatest(coalesce(p_page_size, 900), 1);
$$;

create or replace function public.sync_delete_watched_items(
  p_profile_id integer,
  p_keys jsonb
)
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.watched_items wi
   where wi.user_id = public.current_sync_owner_id()
     and wi.profile_id = p_profile_id
     and exists (
       select 1
         from jsonb_to_recordset(coalesce(p_keys, '[]'::jsonb)) as k(content_id text, season integer, episode integer)
        where wi.content_id = k.content_id
          and wi.season is not distinct from k.season
          and wi.episode is not distinct from k.episode
     );
$$;

create or replace function public.get_sync_overview()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  with owner_id as (
    select public.current_sync_owner_id() as id
  ),
  addon_counts as (
    select profile_id::text as key, count(*)::int as value
      from public.addons a, owner_id o
     where a.user_id = o.id
     group by profile_id
  ),
  plugin_counts as (
    select profile_id::text as key, count(*)::int as value
      from public.plugins p, owner_id o
     where p.user_id = o.id
     group by profile_id
  ),
  library_counts as (
    select profile_id::text as key, count(*)::int as value
      from public.library_items li, owner_id o
     where li.user_id = o.id
     group by profile_id
  ),
  watch_progress_counts as (
    select profile_id::text as key, count(*)::int as value
      from public.watch_progress wp, owner_id o
     where wp.user_id = o.id
     group by profile_id
  ),
  watched_items_counts as (
    select profile_id::text as key, count(*)::int as value
      from public.watched_items wi, owner_id o
     where wi.user_id = o.id
     group by profile_id
  ),
  profiles_map as (
    select coalesce(
      jsonb_object_agg(
        p.profile_index::text,
        jsonb_build_object('name', p.name, 'color', p.avatar_color_hex)
      ),
      '{}'::jsonb
    ) as value
    from public.profiles p, owner_id o
    where p.user_id = o.id
  )
  select jsonb_build_object(
    'addons', coalesce((select jsonb_object_agg(key, value) from addon_counts), '{}'::jsonb),
    'plugins', coalesce((select jsonb_object_agg(key, value) from plugin_counts), '{}'::jsonb),
    'library_items', coalesce((select jsonb_object_agg(key, value) from library_counts), '{}'::jsonb),
    'watch_progress', coalesce((select jsonb_object_agg(key, value) from watch_progress_counts), '{}'::jsonb),
    'watched_items', coalesce((select jsonb_object_agg(key, value) from watched_items_counts), '{}'::jsonb),
    'profiles', (select value from profiles_map)
  );
$$;

create or replace function public.start_tv_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_name text default null
)
returns table(code text, web_url text, expires_at timestamptz, poll_interval_seconds integer)
language plpgsql
security definer
set search_path = public
as $$
begin
  raise exception 'TV login is disabled in this deployment';
end;
$$;

create or replace function public.poll_tv_login_session(
  p_code text,
  p_device_nonce text
)
returns table(status text, expires_at timestamptz, poll_interval_seconds integer)
language plpgsql
security definer
set search_path = public
as $$
begin
  raise exception 'TV login is disabled in this deployment';
end;
$$;

insert into storage.buckets (id, name, public)
values ('avatars', 'avatars', true)
on conflict (id) do update set public = excluded.public;
