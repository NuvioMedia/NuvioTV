import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Play, AlertTriangle, ExternalLink } from "lucide-react";
import { fetchStreams, CINEMETA_BASE } from "@omnio/shared/addon";
import type { AddonStream } from "@omnio/shared/addon";
import { scoreStream, type Compatibility } from "@omnio/shared/codec";
import { PROXY_URL } from "@/lib/proxy";
import { parseProfileId } from "@/lib/profileContext";
import { useAddons } from "@/lib/useAddons";

export const Route = createFileRoute("/p/$profileId/stream/$type/$id")({
  component: StreamPickerPage,
});

interface ScoredStream {
  source: string;
  stream: AddonStream;
  score: number;
  compatibility: Compatibility;
}

function StreamPickerPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);
  const navigate = useNavigate();
  const { data: addons } = useAddons(profileId);

  const sources = (addons && addons.length > 0
    ? addons.filter((a) => a.enabled).map((a) => ({ url: a.url, name: a.name }))
    : [{ url: CINEMETA_BASE, name: "Cinemeta" }]
  );

  const streamQueries = useQuery({
    queryKey: ["streams", params.type, params.id, sources.map((s) => s.url)],
    queryFn: async (): Promise<ScoredStream[]> => {
      const results = await Promise.allSettled(
        sources.map((src) =>
          fetchStreams(src.url, params.type, params.id, { proxyUrl: PROXY_URL }).then((res) =>
            res.streams.map((s) => ({ source: src.name ?? src.url, stream: s }))
          )
        )
      );

      const flat = results.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
      return flat
        .filter((entry) => entry.stream.url || entry.stream.externalUrl)
        .map((entry) => {
          const { score, compatibility } = scoreStream({
            filename: entry.stream.behaviorHints?.filename,
            url: entry.stream.url,
            title: entry.stream.title,
            description: entry.stream.description,
          });
          return { ...entry, score, compatibility };
        })
        .sort((a, b) => b.score - a.score);
    },
  });

  function handlePlay(s: AddonStream) {
    if (!s.url) {
      if (s.externalUrl) window.open(s.externalUrl, "_blank", "noopener");
      return;
    }
    navigate({
      to: "/p/$profileId/player/$type/$id",
      params: { profileId: String(profileId), type: params.type, id: params.id },
      search: { src: s.url },
    });
  }

  return (
    <div className="mx-auto w-full max-w-4xl space-y-4 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Pick a stream</h1>
        <Link
          to="/p/$profileId/detail/$type/$id"
          params={{ profileId: String(profileId), type: params.type, id: params.id.split(":")[0]! }}
          className="text-sm text-slate-400 hover:text-slate-200"
        >
          ← Back
        </Link>
      </div>

      {streamQueries.isLoading && <div className="text-slate-400">Resolving streams…</div>}
      {streamQueries.error && (
        <div className="rounded-lg border border-red-500/40 bg-red-500/10 p-4 text-red-200">
          Failed: {(streamQueries.error as Error).message}
        </div>
      )}

      {streamQueries.data && streamQueries.data.length === 0 && (
        <div className="rounded-lg border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
          <p className="mb-2 font-medium">No streams found.</p>
          <p className="text-sm text-slate-400">
            Add a stream-providing addon (Torrentio + RealDebrid, OpenSubtitles-Stream, etc.) in the
            panel for this profile.
          </p>
        </div>
      )}

      {streamQueries.data && streamQueries.data.length > 0 && (
        <ul className="divide-y divide-slate-800/60 rounded-lg border border-slate-800 bg-slate-900/40">
          {streamQueries.data.map((entry, i) => (
            <li key={i} className="flex items-center gap-3 p-3 hover:bg-slate-800/40">
              <CompatibilityBadge value={entry.compatibility} />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm text-slate-200">
                  {entry.stream.title ?? entry.stream.name ?? "Untitled"}
                </div>
                <div className="text-xs text-slate-500">
                  {entry.source}
                  {entry.stream.behaviorHints?.filename
                    ? ` • ${entry.stream.behaviorHints.filename}`
                    : ""}
                </div>
              </div>
              <button
                type="button"
                onClick={() => handlePlay(entry.stream)}
                disabled={!entry.stream.url && !entry.stream.externalUrl}
                className="flex items-center gap-1 rounded-md bg-slate-800 px-3 py-1.5 text-xs text-slate-200 hover:bg-primary disabled:opacity-40"
              >
                {entry.stream.url ? (
                  <>
                    <Play className="h-3 w-3" /> Play
                  </>
                ) : (
                  <>
                    <ExternalLink className="h-3 w-3" /> Open
                  </>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function CompatibilityBadge({ value }: { value: Compatibility }) {
  if (value === "native")
    return (
      <span className="w-24 rounded-full bg-emerald-500/20 px-2 py-0.5 text-center text-[10px] uppercase tracking-wide text-emerald-300">
        Plays
      </span>
    );
  if (value === "likely")
    return (
      <span className="w-24 rounded-full bg-sky-500/20 px-2 py-0.5 text-center text-[10px] uppercase tracking-wide text-sky-300">
        Likely
      </span>
    );
  if (value === "needsTranscode")
    return (
      <span className="flex w-24 items-center justify-center gap-1 rounded-full bg-amber-500/20 px-2 py-0.5 text-center text-[10px] uppercase tracking-wide text-amber-300">
        <AlertTriangle className="h-3 w-3" />
        Transcode
      </span>
    );
  return (
    <span className="w-24 rounded-full bg-slate-700/50 px-2 py-0.5 text-center text-[10px] uppercase tracking-wide text-slate-400">
      Unknown
    </span>
  );
}
