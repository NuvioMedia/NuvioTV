-- AIOMetadata per-user bridge
--
-- NuvioTV does not host the addon itself; it runs the upstream cedya77/aiometadata
-- Docker image (see infra/aiometadata/). This migration just stores the mapping
-- between a Supabase user and their upstream AIOMetadata config (identified by
-- an opaque UUID minted by upstream on first save).
--
-- Why no encrypted provider_keys table: upstream is the source of truth for
-- provider keys. Duplicating them here only multiplies blast radius.

create table if not exists public.aio_metadata_links (
  user_id uuid primary key references auth.users(id) on delete cascade,
  aio_uuid text not null unique,
  enabled boolean not null default false,
  manifest_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_aio_metadata_links_aio_uuid
  on public.aio_metadata_links (aio_uuid);

-- updated_at trigger ---------------------------------------------------------

create or replace function public.touch_aio_metadata_links_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists trg_aio_metadata_links_updated_at on public.aio_metadata_links;
create trigger trg_aio_metadata_links_updated_at
  before update on public.aio_metadata_links
  for each row execute function public.touch_aio_metadata_links_updated_at();

-- RLS ------------------------------------------------------------------------

alter table public.aio_metadata_links enable row level security;

drop policy if exists aio_links_select_own on public.aio_metadata_links;
create policy aio_links_select_own on public.aio_metadata_links
  for select using (auth.uid() = user_id);

drop policy if exists aio_links_insert_own on public.aio_metadata_links;
create policy aio_links_insert_own on public.aio_metadata_links
  for insert with check (auth.uid() = user_id);

drop policy if exists aio_links_update_own on public.aio_metadata_links;
create policy aio_links_update_own on public.aio_metadata_links
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists aio_links_delete_own on public.aio_metadata_links;
create policy aio_links_delete_own on public.aio_metadata_links
  for delete using (auth.uid() = user_id);

grant select, insert, update, delete on public.aio_metadata_links to authenticated;
