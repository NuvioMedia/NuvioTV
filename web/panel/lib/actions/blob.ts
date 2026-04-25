"use server";

import { revalidatePath } from "next/cache";
import { createServerSupabase } from "@/lib/supabase/server";
import {
  encodeFeature,
  FEATURE_SCHEMAS,
  type SyncedFeatureKey,
} from "@/lib/settings/schemas";
import type { ActionResult } from "./result";

// Wraps the partial-merge RPC. Caller passes decoded UI values + the updated_at
// they last saw; the RPC encodes via jsonb_set so concurrent edits to OTHER
// features in the blob are not clobbered, and refuses the write if their feature
// has been touched on the TV side since the page was rendered.

export async function saveBlobFeature(args: {
  profileId: number;
  featureKey: SyncedFeatureKey;
  decodedValues: Record<string, unknown>;
  expectedUpdatedAt: string | null;
  revalidatePath?: string;
}): Promise<ActionResult> {
  const { profileId, featureKey, decodedValues, expectedUpdatedAt } = args;

  // Validate against the Zod schema first so a bad form value is caught
  // before we hit the database. encodeFeature also throws on unknown keys.
  const schema = FEATURE_SCHEMAS[featureKey];
  if (schema) {
    const parsed = schema.safeParse(decodedValues);
    if (!parsed.success) {
      const issues = parsed.error.issues
        .map((i) => `${i.path.join(".")}: ${i.message}`)
        .join("; ");
      return { ok: false, error: `validation: ${issues}` };
    }
  }

  let encoded;
  try {
    encoded = encodeFeature(featureKey, decodedValues);
  } catch (e) {
    return { ok: false, error: `encode: ${(e as Error).message}` };
  }

  const supabase = await createServerSupabase();
  const { data, error } = await supabase.rpc("sync_push_profile_settings_partial", {
    p_profile_id: profileId,
    p_feature_key: featureKey,
    p_feature_json: encoded,
    p_expected_updated_at: expectedUpdatedAt,
  });

  if (error) {
    // Migration 008 raises 'profile_settings_conflict' with code P0001 when
    // the stored row's updated_at is newer than the caller saw.
    if (
      (error as { code?: string }).code === "P0001" &&
      /profile_settings_conflict/.test(error.message ?? "")
    ) {
      return { ok: false, conflict: true };
    }
    return { ok: false, error: error.message };
  }

  if (args.revalidatePath) revalidatePath(args.revalidatePath);
  return { ok: true, updatedAt: typeof data === "string" ? data : null };
}
