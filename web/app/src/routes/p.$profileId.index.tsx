import { createFileRoute, Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { fetchCatalog, CINEMETA_BASE } from "@omnio/shared/addon";
import type { MetaPreview } from "@omnio/shared/addon";
import { PROXY_URL } from "@/lib/proxy";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/")({
  component: HomePage,
});

const ROWS = [
  { type: "movie", id: "top", title: "Popular Movies" },
  { type: "series", id: "top", title: "Popular Series" },
  { type: "movie", id: "year", title: "Recent Movies" },
];

function HomePage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);

  return (
    <div className="mx-auto w-full max-w-7xl space-y-10 p-6">
      <h1 className="text-2xl font-semibold">Home</h1>
      {ROWS.map((row) => (
        <CatalogRow
          key={`${row.type}/${row.id}`}
          profileId={profileId}
          type={row.type}
          catalogId={row.id}
          title={row.title}
        />
      ))}
    </div>
  );
}

function CatalogRow({
  profileId,
  type,
  catalogId,
  title,
}: {
  profileId: number;
  type: string;
  catalogId: string;
  title: string;
}) {
  const { data, isLoading, error } = useQuery({
    queryKey: ["catalog", CINEMETA_BASE, type, catalogId],
    queryFn: () => fetchCatalog(CINEMETA_BASE, type, catalogId, {}, { proxyUrl: PROXY_URL }),
  });

  return (
    <section>
      <h2 className="mb-3 text-lg font-medium text-slate-200">{title}</h2>
      {isLoading && <SkeletonRow />}
      {error && (
        <div className="rounded-lg border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-200">
          Failed to load — {(error as Error).message}
        </div>
      )}
      {data && (
        <div className="scroll-row -mx-2 flex gap-3 overflow-x-auto px-2 pb-2">
          {data.metas.map((meta) => (
            <PosterCard key={meta.id} profileId={profileId} meta={meta} contentType={type} />
          ))}
        </div>
      )}
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
