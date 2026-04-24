-- Migration: partial merge for profile_settings blob.
-- Lets the web panel overwrite one feature subtree at a time without clobbering the rest
-- of the blob. Optional optimistic concurrency via p_expected_updated_at: if the stored
-- row is newer than the caller saw, the RPC raises profile_settings_conflict and the
-- caller is expected to re-pull and reapply the pending diff.

create or replace function public.sync_push_profile_settings_partial(
  p_profile_id integer,
  p_feature_key text,
  p_feature_json jsonb,
  p_expected_updated_at timestamptz default null
)
returns timestamptz
language plpgsql
security definer
set search_path = public
as $$
declare
  owner_user_id uuid;
  current_blob jsonb;
  current_updated_at timestamptz;
  new_blob jsonb;
  new_updated_at timestamptz;
begin
  if p_profile_id is null or p_profile_id < 1 then
    raise exception 'invalid_profile_id' using errcode = '22023';
  end if;
  if p_feature_key is null or length(p_feature_key) = 0 then
    raise exception 'invalid_feature_key' using errcode = '22023';
  end if;

  owner_user_id := public.current_sync_owner_id();

  select settings_json, updated_at
    into current_blob, current_updated_at
    from public.profile_settings
   where user_id = owner_user_id and profile_id = p_profile_id
   for update;

  if current_blob is null then
    current_blob := jsonb_build_object('version', 1, 'features', '{}'::jsonb);
    current_updated_at := null;
  end if;

  if current_blob ? 'features' = false then
    current_blob := jsonb_set(current_blob, '{features}', '{}'::jsonb, true);
  end if;

  if p_expected_updated_at is not null
     and current_updated_at is not null
     and current_updated_at > p_expected_updated_at then
    raise exception 'profile_settings_conflict' using errcode = 'P0001';
  end if;

  new_blob := jsonb_set(
    current_blob,
    array['features', p_feature_key],
    coalesce(p_feature_json, '{}'::jsonb),
    true
  );

  insert into public.profile_settings (user_id, profile_id, settings_json, updated_at)
  values (owner_user_id, p_profile_id, new_blob, now())
  on conflict (user_id, profile_id) do update set
    settings_json = excluded.settings_json,
    updated_at = now()
  returning updated_at into new_updated_at;

  return new_updated_at;
end;
$$;
