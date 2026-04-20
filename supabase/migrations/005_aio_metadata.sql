-- AIOMetadata per-user configs
-- One row per user; stores an opaque URL token, enable flag, non-secret provider toggles,
-- and encrypted API keys (pgsodium TCE).

create extension if not exists pgcrypto;
create extension if not exists pgsodium;

-- Table ----------------------------------------------------------------------

create table if not exists public.aio_metadata_configs (
  user_id uuid primary key references auth.users(id) on delete cascade,
  token text not null unique,
  enabled boolean not null default false,
  providers jsonb not null default '{}'::jsonb,
  provider_keys jsonb not null default '{}'::jsonb,
  manifest_base_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_aio_metadata_configs_token
  on public.aio_metadata_configs (token);

-- Encrypt provider_keys at rest via pgsodium TCE.
-- pgsodium manages the key id; the application reads the decrypted value via
-- the auto-created view `pgsodium.decrypted_aio_metadata_configs` (service role only)
-- or via the SECURITY DEFINER helpers below for the row owner.
security label for pgsodium
  on column public.aio_metadata_configs.provider_keys
  is 'ENCRYPT WITH KEY COLUMN user_id ASSOCIATED (user_id) NONCE NULL';

-- updated_at trigger ---------------------------------------------------------

create or replace function public.touch_aio_metadata_configs_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists trg_aio_metadata_configs_updated_at on public.aio_metadata_configs;
create trigger trg_aio_metadata_configs_updated_at
  before update on public.aio_metadata_configs
  for each row execute function public.touch_aio_metadata_configs_updated_at();

-- RLS ------------------------------------------------------------------------

alter table public.aio_metadata_configs enable row level security;

drop policy if exists aio_select_own on public.aio_metadata_configs;
create policy aio_select_own on public.aio_metadata_configs
  for select using (auth.uid() = user_id);

drop policy if exists aio_insert_own on public.aio_metadata_configs;
create policy aio_insert_own on public.aio_metadata_configs
  for insert with check (auth.uid() = user_id);

drop policy if exists aio_update_own on public.aio_metadata_configs;
create policy aio_update_own on public.aio_metadata_configs
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Helpers --------------------------------------------------------------------

-- URL-safe base64 without padding. 32 random bytes → 43 chars.
create or replace function public.generate_aio_metadata_token()
returns text
language sql
volatile
set search_path = public
as $$
  select translate(encode(gen_random_bytes(32), 'base64'), '+/=', '-_');
$$;

-- RPCs -----------------------------------------------------------------------

-- Ensure a config row exists for the calling user; return its token.
create or replace function public.ensure_aio_metadata_token()
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  caller uuid := auth.uid();
  existing text;
  new_token text;
begin
  if caller is null then
    raise exception 'ensure_aio_metadata_token requires authenticated caller';
  end if;

  select token into existing from public.aio_metadata_configs where user_id = caller;
  if existing is not null then
    return existing;
  end if;

  loop
    new_token := public.generate_aio_metadata_token();
    exit when not exists (select 1 from public.aio_metadata_configs where token = new_token);
  end loop;

  insert into public.aio_metadata_configs (user_id, token)
  values (caller, new_token);

  return new_token;
end;
$$;

grant execute on function public.ensure_aio_metadata_token() to authenticated;

-- Rotate the caller's token. Old URLs stop resolving immediately.
create or replace function public.rotate_aio_metadata_token()
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  caller uuid := auth.uid();
  new_token text;
begin
  if caller is null then
    raise exception 'rotate_aio_metadata_token requires authenticated caller';
  end if;

  loop
    new_token := public.generate_aio_metadata_token();
    exit when not exists (select 1 from public.aio_metadata_configs where token = new_token);
  end loop;

  update public.aio_metadata_configs
    set token = new_token
    where user_id = caller;

  if not found then
    insert into public.aio_metadata_configs (user_id, token)
    values (caller, new_token);
  end if;

  return new_token;
end;
$$;

grant execute on function public.rotate_aio_metadata_token() to authenticated;

-- Return the caller's decrypted config row. Used by the web dashboard and the
-- Android client to read provider keys without exposing the pgsodium decrypted view.
create or replace function public.get_my_aio_metadata_config()
returns table (
  user_id uuid,
  token text,
  enabled boolean,
  providers jsonb,
  provider_keys jsonb,
  manifest_base_url text,
  created_at timestamptz,
  updated_at timestamptz
)
language sql
security definer
set search_path = public, pgsodium
as $$
  select
    c.user_id,
    c.token,
    c.enabled,
    c.providers,
    d.provider_keys,
    c.manifest_base_url,
    c.created_at,
    c.updated_at
  from public.aio_metadata_configs c
  join pgsodium.decrypted_aio_metadata_configs d on d.user_id = c.user_id
  where c.user_id = auth.uid();
$$;

grant execute on function public.get_my_aio_metadata_config() to authenticated;

-- Internal helper the Edge Function calls (service role only). Returns the
-- decrypted config for a token, or nothing.
create or replace function public.get_aio_metadata_by_token(p_token text)
returns table (
  user_id uuid,
  token text,
  enabled boolean,
  providers jsonb,
  provider_keys jsonb
)
language sql
security definer
set search_path = public, pgsodium
as $$
  select
    c.user_id,
    c.token,
    c.enabled,
    c.providers,
    d.provider_keys
  from public.aio_metadata_configs c
  join pgsodium.decrypted_aio_metadata_configs d on d.user_id = c.user_id
  where c.token = p_token;
$$;

revoke all on function public.get_aio_metadata_by_token(text) from public, anon, authenticated;
grant execute on function public.get_aio_metadata_by_token(text) to service_role;

-- Permissions ----------------------------------------------------------------
-- Grants for authenticated users (RLS policies still apply):
grant select, insert, update on public.aio_metadata_configs to authenticated;
