import { useQuery } from "@tanstack/react-query";
import { supabase } from "./supabase";

export interface PluginRow {
  id: string;
  url: string;
  name: string | null;
  enabled: boolean;
  sort_order: number;
  profile_id: number;
  user_id: string;
  created_at: string;
  updated_at: string;
}

export function useEnabledPlugins(profileId: number) {
  return useQuery({
    queryKey: ["plugins", profileId, "enabled"],
    queryFn: async (): Promise<PluginRow[]> => {
      const { data, error } = await supabase
        .from("plugins")
        .select("*")
        .eq("profile_id", profileId)
        .eq("enabled", true)
        .order("sort_order", { ascending: true });
      if (error) throw error;
      return (data ?? []) as PluginRow[];
    },
  });
}
