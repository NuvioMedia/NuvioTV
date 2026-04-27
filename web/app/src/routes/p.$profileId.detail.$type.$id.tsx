import { useState } from "react";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Calendar, Star, Tag, Play } from "lucide-react";
import { fetchMeta, CINEMETA_BASE } from "@omnio/shared/addon";
import type { MetaVideo } from "@omnio/shared/addon";
import { PROXY_URL } from "@/lib/proxy";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/detail/$type/$id")({
  component: DetailPage,
});

function DetailPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);

  const { data, isLoading, error } = useQuery({
    queryKey: ["meta", params.type, params.id],
    queryFn: () => fetchMeta(CINEMETA_BASE, params.type, params.id, { proxyUrl: PROXY_URL }),
  });

  if (isLoading) {
    return <div className="p-6 text-slate-400">Loading…</div>;
  }
  if (error || !data) {
    return (
      <div className="p-6">
        <div className="rounded-lg border border-red-500/40 bg-red-500/10 p-4 text-red-200">
          Failed to load metadata: {(error as Error)?.message ?? "unknown"}
        </div>
      </div>
    );
  }

  const meta = data.meta;
  const isSeries = params.type === "series";
  const firstEpisode = meta.videos?.find((v) => v.season != null && v.episode != null);

  return (
    <div className="relative">
      {meta.background && (
        <div
          className="absolute inset-x-0 top-0 h-[40vh] bg-cover bg-center opacity-30"
          style={{ backgroundImage: `url(${meta.background})` }}
        />
      )}
      <div className="absolute inset-x-0 top-0 h-[40vh] bg-gradient-to-b from-transparent to-slate-950" />
      <div className="relative mx-auto w-full max-w-7xl space-y-6 p-6 pt-16">
        <div className="flex flex-col gap-6 sm:flex-row">
          {meta.poster && (
            <img
              src={meta.poster}
              alt={meta.name}
              className="aspect-[2/3] w-44 shrink-0 rounded-lg border border-slate-800 object-cover sm:w-56"
            />
          )}
          <div className="flex-1 space-y-4">
            <h1 className="text-3xl font-semibold tracking-tight">{meta.name}</h1>
            <div className="flex flex-wrap gap-3 text-sm text-slate-400">
              {meta.releaseInfo && (
                <span className="flex items-center gap-1">
                  <Calendar className="h-4 w-4" />
                  {meta.releaseInfo}
                </span>
              )}
              {meta.imdbRating && (
                <span className="flex items-center gap-1">
                  <Star className="h-4 w-4 text-amber-400" />
                  {meta.imdbRating}
                </span>
              )}
              {meta.genres && meta.genres.length > 0 && (
                <span className="flex items-center gap-1">
                  <Tag className="h-4 w-4" />
                  {meta.genres.slice(0, 3).join(" • ")}
                </span>
              )}
            </div>
            {meta.description && (
              <p className="max-w-3xl text-sm leading-relaxed text-slate-300">
                {meta.description}
              </p>
            )}
            <div className="flex gap-3 pt-2">
              {!isSeries ? (
                <Link
                  to="/p/$profileId/stream/$type/$id"
                  params={{
                    profileId: String(profileId),
                    type: params.type,
                    id: meta.id,
                  }}
                  className="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 font-medium text-white hover:bg-primary-hover"
                >
                  <Play className="h-4 w-4" />
                  Play
                </Link>
              ) : firstEpisode ? (
                <Link
                  to="/p/$profileId/stream/$type/$id"
                  params={{
                    profileId: String(profileId),
                    type: params.type,
                    id: `${meta.id}:${firstEpisode.season}:${firstEpisode.episode}`,
                  }}
                  className="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 font-medium text-white hover:bg-primary-hover"
                >
                  <Play className="h-4 w-4" />
                  Play S{firstEpisode.season}E{firstEpisode.episode}
                </Link>
              ) : null}
            </div>
          </div>
        </div>

        {isSeries && meta.videos && meta.videos.length > 0 && (
          <SeasonsView
            videos={meta.videos}
            metaId={meta.id}
            type={params.type}
            profileId={profileId}
          />
        )}
      </div>
    </div>
  );
}

// Group videos by season, with a "Specials" bucket for season 0 / missing.
// Cinemeta sometimes returns every episode flat under season 0 when its TVDB
// data is patchy — we still expose them under a "Specials" tab so the user can
// pick something rather than seeing 46 ungrouped entries.
function groupBySeason(
  videos: MetaVideo[]
): { key: number; label: string; videos: MetaVideo[] }[] {
  const groups = new Map<number, MetaVideo[]>();
  for (const v of videos) {
    if (v.episode == null) continue;
    const season = v.season ?? 0;
    const list = groups.get(season) ?? [];
    list.push(v);
    groups.set(season, list);
  }
  const sorted = Array.from(groups.entries()).sort((a, b) => {
    // Specials (0) always last unless it's the only group.
    if (a[0] === 0) return 1;
    if (b[0] === 0) return -1;
    return a[0] - b[0];
  });
  return sorted.map(([key, vids]) => ({
    key,
    label: key === 0 ? "Specials" : `Season ${key}`,
    videos: vids.sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0)),
  }));
}

function SeasonsView({
  videos,
  metaId,
  type,
  profileId,
}: {
  videos: MetaVideo[];
  metaId: string;
  type: string;
  profileId: number;
}) {
  const groups = groupBySeason(videos);
  const defaultKey = groups.find((g) => g.key !== 0)?.key ?? groups[0]?.key ?? 1;
  const [activeKey, setActiveKey] = useState<number>(defaultKey);

  if (groups.length === 0) return null;
  const active = groups.find((g) => g.key === activeKey) ?? groups[0]!;

  return (
    <section>
      <div className="mb-3 flex items-baseline justify-between">
        <h2 className="text-lg font-medium">Episodes</h2>
        <span className="text-xs text-slate-500">
          {active.videos.length} {active.videos.length === 1 ? "episode" : "episodes"}
        </span>
      </div>

      {groups.length > 1 && (
        <div className="scroll-row mb-3 -mx-2 flex gap-1 overflow-x-auto px-2">
          {groups.map((g) => (
            <button
              key={g.key}
              type="button"
              onClick={() => setActiveKey(g.key)}
              className={`shrink-0 rounded-full px-3 py-1.5 text-xs ${
                g.key === active.key
                  ? "bg-primary text-white"
                  : "bg-slate-800 text-slate-300 hover:bg-slate-700"
              }`}
            >
              {g.label}
            </button>
          ))}
        </div>
      )}

      <ul className="divide-y divide-slate-800/60 rounded-lg border border-slate-800 bg-slate-900/40">
        {active.videos.map((v) => (
          <li key={v.id}>
            <Link
              to="/p/$profileId/stream/$type/$id"
              params={{
                profileId: String(profileId),
                type,
                id: `${metaId}:${v.season ?? 0}:${v.episode}`,
              }}
              className="flex items-center gap-3 p-3 transition hover:bg-slate-800/40"
            >
              <div className="w-16 shrink-0 text-xs text-slate-500">
                {active.key === 0 ? "Special" : `S${v.season}·E${v.episode}`}
              </div>
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm text-slate-200">{v.title}</div>
                {v.overview && (
                  <div className="line-clamp-2 text-xs text-slate-500">{v.overview}</div>
                )}
              </div>
              <span className="flex h-9 min-w-[44px] items-center gap-1 rounded-md bg-slate-800 px-3 text-xs text-slate-200">
                <Play className="h-3 w-3" /> Play
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
