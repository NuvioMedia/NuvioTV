import { createFileRoute, Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { fetchCatalog } from "@omnio/shared/addon";
import type { MetaPreview } from "@omnio/shared/addon";
import { PROXY_URL } from "@/lib/proxy";
import { parseProfileId } from "@/lib/profileContext";
import {
  useEnabledAddons,
  useAddonManifests,
  flattenCatalogs,
  type CatalogRow,
} from "@/lib/useAddonManifests";

export const Route = createFileRoute("/p/$profileId/")({
  component: HomePage,
});

function HomePage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);

  const { data: addons = [], isLoading: addonsLoading } = useEnabledAddons(profileId);
  const manifests = useAddonManifests(addons);
  const rows = flattenCatalogs(manifests, 12);

  const allManifestsLoaded = manifests.every((m) => m.manifest !== null || m.error !== null);

  return (
    <div className="mx-auto w-full max-w-7xl space-y-10 p-6">
      <h1 className="text-2xl font-semibold">Home</h1>
      {addonsLoading || !allManifestsLoaded ? (
        <div className="text-slate-400">Loading addons…</div>
      ) : rows.length === 0 ? (
        <div className="rounded-lg border border-slate-800 bg-slate-900/40 p-6 text-sm text-slate-400">
          No catalogs available. Make sure your addons are enabled in the panel.
        </div>
      ) : (
        rows.map((row) => (
          <CatalogSection
            key={`${row.addonUrl}|${row.catalog.type}|${row.catalog.id}`}
            profileId={profileId}
            row={row}
          />
        ))
      )}
    </div>
  );
}

function CatalogSection({ profileId, row }: { profileId: number; row: CatalogRow }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ["catalog", row.addonUrl, row.catalog.type, row.catalog.id],
    queryFn: () =>
      fetchCatalog(row.addonUrl, row.catalog.type, row.catalog.id, {}, { proxyUrl: PROXY_URL }),
    staleTime: 5 * 60_000,
  });

  if (error) {
    // Silent skip — addon-row errors shouldn't break the whole page.
    return null;
  }

  if (isLoading) {
    return (
      <section>
        <h2 className="mb-3 text-lg font-medium text-slate-200">{row.catalog.name}</h2>
        <SkeletonRow />
      </section>
    );
  }

  if (!data || data.metas.length === 0) return null;

  return (
    <section>
      <div className="mb-3 flex items-baseline justify-between">
        <h2 className="text-lg font-medium text-slate-200">{row.catalog.name}</h2>
        <span className="text-xs text-slate-500">{row.addonName}</span>
      </div>
      <div className="scroll-row -mx-2 flex gap-3 overflow-x-auto px-2 pb-2">
        {data.metas.slice(0, 30).map((meta) => (
          <PosterCard
            key={meta.id}
            profileId={profileId}
            meta={meta}
            contentType={row.catalog.type}
          />
        ))}
      </div>
    </section>
  );
}

function PosterCard({
  profileId,
  meta,
  contentType,
}: {
  profileId: number;
  meta: MetaPreview;
  contentType: string;
}) {
  return (
    <Link
      to="/p/$profileId/detail/$type/$id"
      params={{
        profileId: String(profileId),
        type: contentType,
        id: meta.id,
      }}
      className="group block w-36 shrink-0 sm:w-44"
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
          <div className="flex h-full items-center justify-center px-3 text-center text-xs text-slate-500">
            {meta.name}
          </div>
        )}
      </div>
      <div className="mt-2 line-clamp-2 text-sm text-slate-300">{meta.name}</div>
      {meta.releaseInfo && (
        <div className="text-xs text-slate-500">{meta.releaseInfo}</div>
      )}
    </Link>
  );
}

function SkeletonRow() {
  return (
    <div className="scroll-row -mx-2 flex gap-3 overflow-x-auto px-2 pb-2">
      {Array.from({ length: 8 }).map((_, i) => (
        <div
          key={i}
          className="aspect-[2/3] w-36 shrink-0 animate-pulse rounded-lg bg-slate-800/50 sm:w-44"
        />
      ))}
    </div>
  );
}
