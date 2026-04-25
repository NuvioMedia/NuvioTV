import { createServerSupabase } from "@/lib/supabase/server";

export interface Profile {
  id: string;
  user_id: string;
  profile_index: number;
  name: string;
  avatar_color_hex: string;
  uses_primary_addons: boolean;
  uses_primary_plugins: boolean;
  avatar_id: string | null;
  pin_hash: string | null;
  created_at: string;
  updated_at: string;
}

export async function listProfiles(): Promise<Profile[]> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase
    .from("profiles")
    .select("*")
    .order("profile_index", { ascending: true });
  if (error) throw error;
  return (data ?? []) as Profile[];
}

export async function getProfile(profileIndex: number): Promise<Profile | null> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase
    .from("profiles")
    .select("*")
    .eq("profile_index", profileIndex)
    .maybeSingle();
  if (error) throw error;
  return (data as Profile) ?? null;
}

export interface AvatarEntry {
  id: string;
  display_name: string;
  storage_path: string;
  category: string;
  bg_color: string | null;
}

export async function listAvatarCatalog(): Promise<AvatarEntry[]> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase.rpc("get_avatar_catalog");
  if (error) throw error;
  return ((data as AvatarEntry[]) ?? []).map((a) => ({
    id: a.id,
    display_name: a.display_name,
    storage_path: a.storage_path,
    category: a.category,
    bg_color: a.bg_color ?? null,
  }));
}
