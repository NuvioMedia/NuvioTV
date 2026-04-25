"use server";

import { revalidatePath } from "next/cache";
import { createServerSupabase } from "@/lib/supabase/server";
import type { ActionResult } from "./result";

// Addons RPC (sync_push_addons) only writes url + sort_order — name and
// enabled are not part of the contract. Matches AddonSyncService.kt on the
// TV side. The panel intentionally does not surface enable toggles for
// addons because the TV would overwrite them on its next push.

export async function saveAddons(args: {
  profileId: number;
  addonUrls: string[];
  revalidatePath?: string;
}): Promise<ActionResult> {
  const seen = new Set<string>();
  const cleaned: { url: string; sort_order: number }[] = [];
  for (const raw of args.addonUrls) {
    const url = raw.trim();
    if (url.length === 0) continue;
    if (seen.has(url)) continue;
    seen.add(url);
    cleaned.push({ url, sort_order: cleaned.length });
  }

  const supabase = await createServerSupabase();
  const { error } = await supabase.rpc("sync_push_addons", {
    p_addons: cleaned,
    p_profile_id: args.profileId,
  });
  if (error) return { ok: false, error: error.message };

  if (args.revalidatePath) revalidatePath(args.revalidatePath);
  return { ok: true, updatedAt: null };
}

// Plugins RPC (sync_push_plugins) writes url + name + enabled + sort_order.
// The plugin's JS code lives on disk on the TV (plugin_code/...) and is
// intentionally not synced; only metadata flows through this RPC.

export interface PluginInput {
  url: string;
  name: string | null;
  enabled: boolean;
}

export async function savePlugins(args: {
  profileId: number;
  plugins: PluginInput[];
  revalidatePath?: string;
}): Promise<ActionResult> {
  const seen = new Set<string>();
  const payload: Array<{
    url: string;
    name: string;
    enabled: boolean;
    sort_order: number;
  }> = [];
  for (const p of args.plugins) {
    const url = p.url.trim();
    if (url.length === 0) continue;
    if (seen.has(url)) continue;
    seen.add(url);
    payload.push({
      url,
      name: p.name?.trim() ?? "",
      enabled: p.enabled,
      sort_order: payload.length,
    });
  }

  const supabase = await createServerSupabase();
  const { error } = await supabase.rpc("sync_push_plugins", {
    p_plugins: payload,
    p_profile_id: args.profileId,
  });
  if (error) return { ok: false, error: error.message };

  if (args.revalidatePath) revalidatePath(args.revalidatePath);
  return { ok: true, updatedAt: null };
}

// Collections RPC (sync_push_collections) overwrites the full list as a JSONB
// array. The TV side decodes this via Gson into SerializableCollection — keys
// must remain camelCase. The panel does not validate every field; it preserves
// what it pulled and only edits the user-facing fields (title, backdrop, pin,
// folder title/cover/shape).

export async function saveCollections(args: {
  profileId: number;
  collectionsJson: unknown[];
  revalidatePath?: string;
}): Promise<ActionResult> {
  const supabase = await createServerSupabase();
  const { error } = await supabase.rpc("sync_push_collections", {
    p_profile_id: args.profileId,
    p_collections_json: args.collectionsJson,
  });
  if (error) return { ok: false, error: error.message };

  if (args.revalidatePath) revalidatePath(args.revalidatePath);
  return { ok: true, updatedAt: null };
}

// Profile push — full array overwrite via sync_push_profiles. PIN management
// uses set_profile_pin / clear_profile_pin separately and is not exposed in v2.

export interface ProfileInput {
  profile_index: number;
  name: string;
  avatar_color_hex: string;
  uses_primary_addons: boolean;
  uses_primary_plugins: boolean;
  avatar_id: string | null;
}

export async function saveProfiles(args: {
  profiles: ProfileInput[];
  revalidatePath?: string;
}): Promise<ActionResult> {
  const supabase = await createServerSupabase();
  const { error } = await supabase.rpc("sync_push_profiles", {
    p_profiles: args.profiles,
  });
  if (error) return { ok: false, error: error.message };

  if (args.revalidatePath) revalidatePath(args.revalidatePath);
  return { ok: true, updatedAt: null };
}

// Wipes per-profile sync data (addons, plugins, library, watch progress, watched,
// settings). For profile_index = 1 the profile row stays but its PIN is cleared;
// for any other index, the profile row itself is also deleted.

export async function deleteProfileData(args: {
  profileId: number;
  revalidatePath?: string;
}): Promise<ActionResult> {
  const supabase = await createServerSupabase();
  const { error } = await supabase.rpc("sync_delete_profile_data", {
    p_profile_id: args.profileId,
  });
  if (error) return { ok: false, error: error.message };

  if (args.revalidatePath) revalidatePath(args.revalidatePath);
  return { ok: true, updatedAt: null };
}
