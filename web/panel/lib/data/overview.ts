import { createServerSupabase } from "@/lib/supabase/server";

export interface OverviewCounts {
  addons: number;
  plugins: number;
  collections: number;
  libraryItems: number;
  watchedItems: number;
  linkedDevices: number;
  settingsUpdatedAt: string | null;
}

export async function getOverview(profileId: number): Promise<OverviewCounts> {
  const supabase = await createServerSupabase();

  const [addons, plugins, library, watched, devices, settings, collections] = await Promise.all([
    supabase
      .from("addons")
      .select("id", { count: "exact", head: true })
      .eq("profile_id", profileId),
    supabase
      .from("plugins")
      .select("id", { count: "exact", head: true })
      .eq("profile_id", profileId),
    supabase
      .from("library_items")
      .select("id", { count: "exact", head: true })
      .eq("profile_id", profileId),
    supabase
      .from("watched_items")
      .select("id", { count: "exact", head: true })
      .eq("profile_id", profileId),
    supabase.from("linked_devices").select("id", { count: "exact", head: true }),
    supabase
      .from("profile_settings")
      .select("updated_at, settings_json")
      .eq("profile_id", profileId)
      .maybeSingle(),
    supabase.rpc("sync_pull_collections", { p_profile_id: profileId }),
  ]);

  const collectionsRow = (collections.data?.[0] ?? null) as
    | { collections_json?: unknown }
    | null;
  const collectionsArr = Array.isArray(collectionsRow?.collections_json)
    ? (collectionsRow!.collections_json as unknown[])
    : [];

  return {
    addons: addons.count ?? 0,
    plugins: plugins.count ?? 0,
    collections: collectionsArr.length,
    libraryItems: library.count ?? 0,
    watchedItems: watched.count ?? 0,
    linkedDevices: devices.count ?? 0,
    settingsUpdatedAt: (settings.data?.updated_at as string | undefined) ?? null,
  };
}
