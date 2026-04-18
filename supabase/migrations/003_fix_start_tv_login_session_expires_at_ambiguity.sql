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

  update public.tv_login_sessions as tls
     set status = 'expired'
   where tls.status in ('pending', 'approved')
     and tls.expires_at <= now();

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