create table if not exists public.tv_login_sessions (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  device_nonce_hash text not null,
  device_name text,
  redirect_base_url text not null,
  status text not null default 'pending' check (status in ('pending', 'approved', 'used', 'expired', 'cancelled')),
  created_by_user_id uuid not null references auth.users(id) on delete cascade,
  approved_by_user_id uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  approved_at timestamptz,
  exchanged_at timestamptz,
  poll_interval_seconds integer not null default 3 check (poll_interval_seconds between 2 and 30)
);

create index if not exists idx_tv_login_sessions_status_expires
  on public.tv_login_sessions (status, expires_at);

alter table public.tv_login_sessions enable row level security;

create or replace function public.hash_tv_login_nonce(p_device_nonce text)
returns text
language sql
immutable
set search_path = public
as $$
  select encode(sha256(convert_to(p_device_nonce, 'UTF8')), 'hex');
$$;

create or replace function public.generate_tv_login_code()
returns text
language plpgsql
volatile
set search_path = public
as $$
declare
  candidate text;
begin
  loop
    candidate := upper(substr(encode(gen_random_bytes(6), 'hex'), 1, 6));
    exit when not exists (
      select 1
      from public.tv_login_sessions tls
      where tls.code = candidate
        and tls.status in ('pending', 'approved')
        and tls.expires_at > now()
    );
  end loop;

  return candidate;
end;
$$;

create or replace function public.start_tv_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_name text default null
)
returns table(
  code text,
  web_url text,
  expires_at timestamptz,
  poll_interval_seconds integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  actor_user_id uuid;
  generated_code text;
  ttl timestamptz;
  sanitized_redirect_base_url text;
  device_nonce_hash text;
begin
  actor_user_id := auth.uid();
  if actor_user_id is null then
    raise exception 'Not authenticated';
  end if;

  if coalesce(length(trim(p_device_nonce)), 0) < 16 then
    raise exception 'Invalid device nonce';
  end if;

  sanitized_redirect_base_url := trim(coalesce(p_redirect_base_url, ''));
  if sanitized_redirect_base_url = '' or sanitized_redirect_base_url !~ '^https://.+' then
    raise exception 'Invalid TV login redirect base URL';
  end if;

  update public.tv_login_sessions
     set status = 'expired'
   where status in ('pending', 'approved')
     and expires_at <= now();

  generated_code := public.generate_tv_login_code();
  ttl := now() + interval '10 minutes';
  device_nonce_hash := public.hash_tv_login_nonce(trim(p_device_nonce));

  insert into public.tv_login_sessions (
    code,
    device_nonce_hash,
    device_name,
    redirect_base_url,
    status,
    created_by_user_id,
    expires_at,
    poll_interval_seconds
  ) values (
    generated_code,
    device_nonce_hash,
    nullif(trim(p_device_name), ''),
    sanitized_redirect_base_url,
    'pending',
    actor_user_id,
    ttl,
    3
  );

  return query
  select
    generated_code,
    sanitized_redirect_base_url || case when position('?' in sanitized_redirect_base_url) > 0 then '&' else '?' end || 'code=' || generated_code,
    ttl,
    3;
end;
$$;

create or replace function public.poll_tv_login_session(
  p_code text,
  p_device_nonce text
)
returns table(
  status text,
  expires_at timestamptz,
  poll_interval_seconds integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  session_row public.tv_login_sessions%rowtype;
  nonce_hash text;
begin
  if coalesce(length(trim(p_code)), 0) = 0 then
    raise exception 'Invalid TV login code';
  end if;

  if coalesce(length(trim(p_device_nonce)), 0) < 16 then
    raise exception 'Invalid device nonce';
  end if;

  nonce_hash := public.hash_tv_login_nonce(trim(p_device_nonce));

  select *
    into session_row
    from public.tv_login_sessions tls
   where tls.code = upper(trim(p_code))
   limit 1;

  if not found then
    raise exception 'Invalid TV login code';
  end if;

  if session_row.device_nonce_hash <> nonce_hash then
    raise exception 'Invalid device nonce';
  end if;

  if session_row.expires_at <= now() and session_row.status in ('pending', 'approved') then
    update public.tv_login_sessions
       set status = 'expired'
     where id = session_row.id;
    session_row.status := 'expired';
  end if;

  return query
  select session_row.status, session_row.expires_at, session_row.poll_interval_seconds;
end;
$$;

create or replace function public.approve_tv_login_session(p_code text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  actor_user_id uuid;
  is_anonymous_user boolean;
begin
  actor_user_id := auth.uid();
  if actor_user_id is null then
    raise exception 'Not authenticated';
  end if;

  is_anonymous_user := coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false);
  if is_anonymous_user then
    raise exception 'Full account required to approve TV login';
  end if;

  update public.tv_login_sessions
     set status = 'approved',
         approved_by_user_id = actor_user_id,
         approved_at = now()
   where code = upper(trim(p_code))
     and status = 'pending'
     and expires_at > now();

  if not found then
    raise exception 'Invalid or expired TV login code';
  end if;
end;
$$;
