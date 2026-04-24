import { createServerSupabase } from "@/lib/supabase/server";

export interface LibraryItem {
  id: string;
  content_id: string;
  content_type: string;
  name: string;
  poster: string | null;
  poster_shape: string;
  background: string | null;
  description: string | null;
  release_info: string | null;
  imdb_rating: number | null;
  genres: string[];
  addon_base_url: string | null;
  added_at: number;
  profile_id: number;
  updated_at: string;
}

export interface WatchedItem {
  id: string;
  content_id: string;
  content_type: string;
  title: string;
  season: number | null;
  episode: number | null;
  watched_at: number;
  profile_id: number;
  updated_at: string;
}

export async function listLibrary(profileId: number, limit = 200): Promise<LibraryItem[]> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase
    .from("library_items")
    .select("*")
    .eq("profile_id", profileId)
    .order("added_at", { ascending: false })
    .limit(limit);
  if (error) throw error;
  return (data ?? []) as LibraryItem[];
}

export async function listRecentlyWatched(
  profileId: number,
  limit = 100
): Promise<WatchedItem[]> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase
    .from("watched_items")
    .select("*")
    .eq("profile_id", profileId)
    .order("watched_at", { ascending: false })
    .limit(limit);
  if (error) throw error;
  return (data ?? []) as WatchedItem[];
}
