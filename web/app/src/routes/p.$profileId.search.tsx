import { useEffect, useState } from "react";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Loader2, Search as SearchIcon } from "lucide-react";
import { fetchCatalog } from "@omnio/shared/addon";
import { PROXY_URL } from "@/lib/proxy";
import { parseProfileId } from "@/lib/profileContext";
import {
  useEnabledAddons,
  useAddonManifests,
  searchableCatalogs,
} from "@/lib/useAddonManifests";

export const Route = createFileRoute("/p/$profileId/search")({
  component: SearchPage,
});

function SearchPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);

  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedQuery(query.trim()), 350);
    return () => window.clearTimeout(t);
  }, [query]);

  const { data: addons = [] } = useEnabledAddons(profileId);
  const manifests = useAddonManifests(addons);
  const catalogs = searchableCatalogs(manifests);

  const { data, isLoading, error } = useQuery({
    enabled: debouncedQuery.length >= 2 && catalogs.length > 0,
    queryKey: ["search", debouncedQuery, catalogs.map((c) => `${c.addonUrl}|${c.catalog.id}`)],
    queryFn: async () => {
      const results = await Promise.allSettled(
        catalogs.map((c) =>
          fetchCatalog(
            c.addonUrl,
            c.catalog.type,
            c.catalog.id,
            { search: debouncedQuery },
            { proxyUrl: PROXY_URL }
          ).then((res) => ({
            source: c.addonName,
            type: c.catalog.type,
            metas: res.metas,
          }))
        )
      );
      return results
        .flatMap((r) => (r.status === "fulfilled" ? [r.value] : []))
        .filter((g) => g.metas.length > 0);
    },
  });

  return (
    <div className="mx-auto w-full max-w-7xl space-y-6 p-6">
      <h1 className="text-2xl font-semibold">Search</h1>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search across your addons…"
          autoFocus
          className="w-full rounded-lg border border-slate-700 bg-slate-900/60 py-3 pl-10 pr-4 text-slate-100 outline-none focus:border-primary"
        />
        {isLoading && (
          <Loader2 className="absolute right-3 top-1/2 h-5 w-5 -translate-y-1/2 animate-spin text-slate-500" />
        )}
      </div>

      {error && (
        <div className="rounded-lg border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-200">
          {(error as Error).message}
        </div>
      )}

      {!isLoading && debouncedQuery.length >= 2 && data && data.length === 0 && (
        <div className="text-sm text-slate-500">No results for "{debouncedQuery}".</div>
      )}

      {data && data.length > 0 && (
        <div className="space-y-8">
          {data.map((group) => (
            <section key={`${group.source}/${group.type}`}>
              <div className="mb-3 flex items-baseline justify-between">
                <h2 className="text-lg font-medium text-slate-200">
                  {group.source} — {group.type}
                </h2>
              </div>
              <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6">
                {group.metas.slice(0, 24).map((meta) => (
                  <Link
                    key={meta.id}
                    to="/p/$profileId/detail/$type/$id"
                    params={{
                      profileId: String(profileId),
                      type: group.type,
                      id: meta.id,
                    }}
                    className="group block"
                  >
                    <div className="aspect-[2/3] overflow-hidden rounded-lg border border-slate-800 bg-slate-900 transition group-hover:border-primary">
                      {meta.poster ? (
                        <img
                          src={meta.poster}
                          alt={meta.name}
                          loading="lazy"
                          className="h-full w-full object-cover"
                        />
                      ) : (
                        <div className="flex h-full items-center justify-center p-2 text-center text-xs text-slate-500">
                          {meta.name}
                        </div>
                      )}
                    </div>
                    <div className="mt-2 line-clamp-2 text-xs text-slate-300">{meta.name}</div>
                  </Link>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
