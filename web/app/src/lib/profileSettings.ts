import { supabase } from "./supabase";

// Minimal envelope handling — full version lives in web/panel/lib/settings/envelope.ts.
// We only need to read/write `emby_credentials` from web/app for Phase 4. Anything
// touching numeric prefs should mirror the int/long/float/double distinction the
// TV cares about; for plain strings the encoding is unambiguous.

interface EncodedString {
  type: "string";
  value: string;
}

interface SettingsBlob {
  version: number;
  features: Record<string, Record<string, EncodedString>>;
}

interface ProfileSettingsRow {
  profile_id: number;
  settings_json: SettingsBlob;
  updated_at: string;
}

export async function pullProfileSettings(profileId: number): Promise<ProfileSettingsRow | null> {
  const { data, error } = await supabase.rpc("sync_pull_profile_settings_blob", {
    p_profile_id: profileId,
  });
  if (error) throw error;
  if (!data || (Array.isArray(data) && data.length === 0)) return null;
  const row = Array.isArray(data) ? data[0] : data;
  return row as ProfileSettingsRow;
}

export async function pushFeature(
  profileId: number,
  featureKey: string,
  feature: Record<string, EncodedString>,
  expectedUpdatedAt?: string
): Promise<{ updatedAt: string } | { conflict: true }> {
  const { data, error } = await supabase.rpc("sync_push_profile_settings_partial", {
    p_profile_id: profileId,
    p_feature_key: featureKey,
    p_feature_json: feature,
    p_expected_updated_at: expectedUpdatedAt ?? null,
  });
  if (error) {
    if (error.message?.includes("profile_settings_conflict")) {
      return { conflict: true };
    }
    throw error;
  }
  return { updatedAt: String(data) };
}

export function decodeStringFeature(
  blob: SettingsBlob | null,
  featureKey: string
): Record<string, string> {
  if (!blob?.features?.[featureKey]) return {};
  const feature = blob.features[featureKey];
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(feature)) {
    if (v?.type === "string" && typeof v.value === "string") out[k] = v.value;
  }
  return out;
}

export function encodeStringFeature(values: Record<string, string>): Record<string, EncodedString> {
  const out: Record<string, EncodedString> = {};
  for (const [k, v] of Object.entries(values)) {
    if (v) out[k] = { type: "string", value: v };
  }
  return out;
}
