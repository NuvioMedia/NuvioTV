import { createServerSupabase } from "@/lib/supabase/server";
import {
  decodeFeature,
  parseBlob,
  type DecodedValue,
  type SettingsBlob,
} from "@/lib/settings/envelope";
import {
  FEATURE_SCHEMAS,
  SYNCED_FEATURES,
  type SyncedFeatureKey,
} from "@/lib/settings/schemas";

export interface SettingsSnapshot {
  raw: SettingsBlob | null;
  features: Partial<Record<SyncedFeatureKey, Record<string, DecodedValue>>>;
  unknownFeatureKeys: string[];
  validationErrors: Partial<Record<SyncedFeatureKey, string>>;
  updatedAt: string | null;
}

export async function getSettingsSnapshot(profileId: number): Promise<SettingsSnapshot> {
  const supabase = await createServerSupabase();

  const { data, error } = await supabase.rpc("sync_pull_profile_settings_blob", {
    p_profile_id: profileId,
  });
  if (error) throw error;

  const row = (data as Array<{ settings_json: unknown; updated_at: string }> | null)?.[0];
  if (!row) {
    return {
      raw: null,
      features: {},
      unknownFeatureKeys: [],
      validationErrors: {},
      updatedAt: null,
    };
  }

  let blob: SettingsBlob;
  try {
    blob = parseBlob(row.settings_json);
  } catch {
    return {
      raw: null,
      features: {},
      unknownFeatureKeys: [],
      validationErrors: {},
      updatedAt: row.updated_at ?? null,
    };
  }

  const features: SettingsSnapshot["features"] = {};
  const validationErrors: SettingsSnapshot["validationErrors"] = {};
  const knownKeys = new Set<string>(SYNCED_FEATURES);
  const unknownFeatureKeys: string[] = [];

  for (const [featureKey, featureValue] of Object.entries(blob.features)) {
    if (!knownKeys.has(featureKey)) {
      unknownFeatureKeys.push(featureKey);
      continue;
    }
    const key = featureKey as SyncedFeatureKey;
    const decoded = decodeFeature(featureValue);
    features[key] = decoded;

    const schema = FEATURE_SCHEMAS[key];
    if (schema) {
      const result = schema.safeParse(decoded);
      if (!result.success) {
        validationErrors[key] = result.error.issues
          .map((i) => `${i.path.join(".")}: ${i.message}`)
          .join("; ");
      }
    }
  }

  return {
    raw: blob,
    features,
    unknownFeatureKeys,
    validationErrors,
    updatedAt: row.updated_at ?? null,
  };
}
