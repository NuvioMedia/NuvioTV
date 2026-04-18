create extension if not exists pgcrypto with schema extensions;

create or replace function public.hash_tv_login_nonce(p_device_nonce text)
returns text
language sql
immutable
set search_path = public
as $$
  select encode(extensions.digest(convert_to(p_device_nonce, 'UTF8'), 'sha256'), 'hex');
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
    candidate := upper(substr(encode(extensions.gen_random_bytes(6), 'hex'), 1, 6));
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