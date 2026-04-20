import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.49.8";

export type AioConfig = {
  user_id: string;
  token: string;
  enabled: boolean;
  providers: Record<string, unknown>;
  provider_keys: Record<string, string>;
};

/** Load a user's AIOMetadata config by opaque token, via the service-role RPC. */
export async function loadConfigByToken(
  admin: SupabaseClient,
  token: string,
): Promise<AioConfig | null> {
  const { data, error } = await admin.rpc("get_aio_metadata_by_token", {
    p_token: token,
  });
  if (error) {
    console.error("loadConfigByToken rpc error", error.message);
    return null;
  }
  const row = Array.isArray(data) ? data[0] : data;
  if (!row) return null;
  return {
    user_id: row.user_id,
    token: row.token,
    enabled: row.enabled,
    providers: (row.providers ?? {}) as Record<string, unknown>,
    provider_keys: (row.provider_keys ?? {}) as Record<string, string>,
  };
}
