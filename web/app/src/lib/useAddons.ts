import { useQuery } from "@tanstack/react-query";
import type { Addon } from "@omnio/shared/supabase";
import { supabase } from "./supabase";

export function useAddons(profileId: number) {
  return useQuery({
    queryKey: ["addons", profileId],
    queryFn: async (): Promise<Addon[]> => {
      const { data, error } = await supabase
        .from("addons")
        .select("*")
        .eq("profile_id", profileId)
        .order("sort_order", { ascending: true });
      if (error) throw error;
      return (data ?? []) as Addon[];
    },
  });
}
