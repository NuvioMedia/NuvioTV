import { createServerSupabase } from "@/lib/supabase/server";

export interface Addon {
  id: string;
  url: string;
  name: string | null;
  enabled: boolean;
  sort_order: number;
  profile_id: number;
  created_at: string;
  updated_at: string;
}

export interface Plugin {
  id: string;
  url: string;
  name: string | null;
  enabled: boolean;
  sort_order: number;
  profile_id: number;
  created_at: string;
  updated_at: string;
}

export async function listAddons(profileId: number): Promise<Addon[]> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase
    .from("addons")
    .select("*")
    .eq("profile_id", profileId)
    .order("sort_order", { ascending: true });
  if (error) throw error;
  return (data ?? []) as Addon[];
}

export async function listPlugins(profileId: number): Promise<Plugin[]> {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase
    .from("plugins")
    .select("*")
    .eq("profile_id", profileId)
    .order("sort_order", { ascending: true });
  if (error) throw error;
  return (data ?? []) as Plugin[];
}
