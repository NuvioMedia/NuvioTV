-- Migration: collections feature
-- Adds profile-scoped collections storage + sync RPCs used by CollectionSyncService.

create table if not exists public.collections (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  profile_id integer not null check (profile_id >= 1),
  collections_json jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now(),
  unique (user_id, profile_id)
);

create index if not exists idx_collections_user_profile on public.collections (user_id, profile_id);

drop trigger if exists trg_collections_set_updated_at on public.collections;
create trigger trg_collections_set_updated_at
before update on public.collections
for each row execute function public.set_updated_at();

alter table public.collections enable row level security;

drop policy if exists collections_access on public.collections;
create policy collections_access on public.collections for all
using (user_id = public.current_sync_owner_id())
with check (user_id = public.current_sync_owner_id());

create or replace function public.sync_push_collections(
  p_profile_id integer,
  p_collections_json jsonb
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

  insert into public.collections (user_id, profile_id, collections_json, updated_at)
  values (owner_user_id, p_profile_id, coalesce(p_collections_json, '[]'::jsonb), now())
  on conflict (user_id, profile_id) do update set
    collections_json = excluded.collections_json,
    updated_at = now();
end;
$$;

create or replace function public.sync_pull_collections(p_profile_id integer)
returns table(profile_id integer, collections_json jsonb, updated_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select c.profile_id, c.collections_json, c.updated_at
    from public.collections c
   where c.user_id = public.current_sync_owner_id()
     and c.profile_id = p_profile_id
   limit 1;
$$;
