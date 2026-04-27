import { useQueries, useQuery } from "@tanstack/react-query";
import { fetchManifest, CINEMETA_BASE } from "@omnio/shared/addon";
import type { AddonManifest, AddonCatalog } from "@omnio/shared/addon";
import type { Addon } from "@omnio/shared/supabase";
import { PROXY_URL } from "./proxy";
import { supabase } from "./supabase";

// Returns the user's enabled addons for a profile. Falls back to Cinemeta
// when nothing is configured yet so the app remains usable for fresh accounts.
export function useEnabledAddons(profileId: number) {
  return useQuery({
    queryKey: ["addons", profileId, "enabled"],
    queryFn: async (): Promise<{ url: string; name: string | null }[]> => {
      const { data, error } = await supabase
        .from("addons")
        .select("*")
        .eq("profile_id", profileId)
        .eq("enabled", true)
        .order("sort_order", { ascending: true });
      if (error) throw error;
      const addons = (data ?? []) as Addon[];
      if (addons.length === 0) {
        return [{ url: CINEMETA_BASE, name: "Cinemeta" }];
      }
      return addons.map((a) => ({ url: a.url, name: a.name }));
    },
  });
}

export interface AddonManifestEntry {
  url: string;
  name: string | null;
  manifest: AddonManifest | null;
  error: Error | null;
}

// Fetches the manifest for each enabled addon in parallel. Failures don't block
// the rest — we want a single broken addon to be a row-level error, not page death.
export function useAddonManifests(addons: { url: string; name: string | null }[]) {
  const results = useQueries({
    queries: addons.map((a) => ({
      queryKey: ["manifest", a.url],
      queryFn: () => fetchManifest(a.url, { proxyUrl: PROXY_URL }),
      staleTime: 30 * 60_000,
    })),
  });

  return addons.map<AddonManifestEntry>((a, i) => ({
    url: a.url,
    name: a.name,
    manifest: results[i]?.data ?? null,
    error: (results[i]?.error as Error) ?? null,
  }));
}

// All catalogs across the user's addons. Useful for the home page rows and the
// "see all" navigation.
export interface CatalogRow {
  addonUrl: string;
  addonName: string;
  catalog: AddonCatalog;
}

export function flattenCatalogs(entries: AddonManifestEntry[], limit = 20): CatalogRow[] {
  const rows: CatalogRow[] = [];
  for (const entry of entries) {
    if (!entry.manifest) continue;
    for (const cat of entry.manifest.catalogs ?? []) {
      // Skip catalogs that demand a required extra (search, genre) — those
      // are surfaced via Search/Discover, not as a default home row.
      const hasRequired = (cat.extra ?? []).some((e) => e.isRequired) || (cat.extraRequired ?? []).length > 0;
      if (hasRequired) continue;
      rows.push({
        addonUrl: entry.url,
        addonName: entry.name ?? entry.manifest.name,
        catalog: cat,
      });
      if (rows.length >= limit) return rows;
    }
  }
  return rows;
}

export function searchableCatalogs(entries: AddonManifestEntry[]): CatalogRow[] {
  const rows: CatalogRow[] = [];
  for (const entry of entries) {
    if (!entry.manifest) continue;
    for (const cat of entry.manifest.catalogs ?? []) {
      const supportsSearch =
        (cat.extra ?? []).some((e) => e.name === "search") ||
        (cat.extraSupported ?? []).includes("search");
      if (supportsSearch) {
        rows.push({
          addonUrl: entry.url,
          addonName: entry.name ?? entry.manifest.name,
          catalog: cat,
        });
      }
    }
  }
  return rows;
}
