import { supabase } from "./supabase";

// Collections are stored as a single jsonb blob per profile under
// public.collections.collections_json. We mirror the Android shape — an array
// of folder objects, each with an item array — so the panel and the TV
// continue to round-trip the data unchanged.

export interface CollectionItem {
  contentId: string;
  contentType: string;
  name: string;
  poster?: string | null;
  background?: string | null;
  addedAt: number;
}

export interface CollectionFolder {
  id: string;
  name: string;
  description?: string | null;
  createdAt: number;
  updatedAt: number;
  items: CollectionItem[];
}

export async function pullCollections(profileId: number): Promise<CollectionFolder[]> {
  const { data, error } = await supabase.rpc("sync_pull_collections", {
    p_profile_id: profileId,
  });
  if (error) throw error;
  if (!data) return [];
  const row = Array.isArray(data) ? data[0] : data;
  if (!row || !row.collections_json) return [];
  // The blob is the array directly. The TV writes it as `[{...}, ...]`.
  if (!Array.isArray(row.collections_json)) return [];
  return row.collections_json as CollectionFolder[];
}

export async function pushCollections(
  profileId: number,
  folders: CollectionFolder[]
): Promise<void> {
  const { error } = await supabase.rpc("sync_push_collections", {
    p_profile_id: profileId,
    p_collections_json: folders,
  });
  if (error) throw error;
}

export function newFolder(name: string): CollectionFolder {
  return {
    id: crypto.randomUUID(),
    name: name.trim() || "Untitled",
    description: null,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    items: [],
  };
}
