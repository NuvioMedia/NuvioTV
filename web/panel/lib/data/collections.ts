import { createServerSupabase } from "@/lib/supabase/server";

// Mirrors SerializableCollection / SerializableFolder / SerializableCatalogSource
// in app/src/main/java/com/omnio/tv/data/local/CollectionsDataStore.kt.
// Gson default = camelCase keys; the sync RPC writes the raw JSON unchanged.
export interface CollectionCatalogSource {
  addonId: string;
  type: string;
  catalogId: string;
  genre?: string | null;
}

export interface CollectionFolder {
  id: string;
  title: string;
  coverImageUrl?: string | null;
  focusGifUrl?: string | null;
  focusGifEnabled?: boolean | null;
  coverEmoji?: string | null;
  tileShape?: string; // "POSTER" | "LANDSCAPE" | "SQUARE"
  hideTitle?: boolean;
  catalogSources?: CollectionCatalogSource[];
}

export interface Collection {
  id: string;
  title: string;
  backdropImageUrl?: string | null;
  pinToTop?: boolean;
  focusGlowEnabled?: boolean | null;
  viewMode?: string; // "TABBED_GRID"
  showAllTab?: boolean;
  folders?: CollectionFolder[];
}

export async function listCollections(profileId: number): Promise<Collection[]> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase.rpc("sync_pull_collections", {
    p_profile_id: profileId,
  });
  if (error) throw error;
  const row = (data as Array<{ collections_json: unknown }> | null)?.[0];
  if (!row) return [];
  const arr = Array.isArray(row.collections_json) ? row.collections_json : [];
  return arr as Collection[];
}
