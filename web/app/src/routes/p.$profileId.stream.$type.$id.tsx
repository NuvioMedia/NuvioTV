import { useState } from "react";
import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Play, AlertTriangle, ExternalLink, Loader2 } from "lucide-react";
import { fetchStreams, CINEMETA_BASE } from "@omnio/shared/addon";
import type { AddonStream } from "@omnio/shared/addon";
import { scoreStream, type Compatibility } from "@omnio/shared/codec";
import { PROXY_URL, probeStream } from "@/lib/proxy";
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
  const [pendingProbeIdx, setPendingProbeIdx] = useState<number | null>(null);
  const [probeWarning, setProbeWarning] = useState<{
    idx: number;
    stream: AddonStream;
    reason: string;
  } | null>(null);

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

  async function handlePlay(idx: number, entry: ScoredStream) {
    const s = entry.stream;
    if (!s.url) {
      if (s.externalUrl) window.open(s.externalUrl, "_blank", "noopener");
      return;
    }

    // Skip probe + go straight to player when filename hints already say "native".
    // For anything weaker, do a HEAD/Range probe so we surface MKV / DTS issues
    // before the user stares at a black <video>.
    if (entry.compatibility !== "native") {
      setPendingProbeIdx(idx);
      try {
        const probe = await probeStream(s.url);
        const reason = probeIssue(probe);
        if (reason) {
          setProbeWarning({ idx, stream: s, reason });
          setPendingProbeIdx(null);
          return;
        }
      } catch (err) {
        console.warn("probe failed, proceeding to play", err);
      } finally {
        setPendingProbeIdx(null);
      }
    }

    navigate({
      to: "/p/$profileId/player/$type/$id",
      params: { profileId: String(profileId), type: params.type, id: params.id },
      search: { src: s.url },
    });
  }

  function playAnyway() {
    if (!probeWarning?.stream.url) return;
    const url = probeWarning.stream.url;
    setProbeWarning(null);
    navigate({
      to: "/p/$profileId/player/$type/$id",
      params: { profileId: String(profileId), type: params.type, id: params.id },
      search: { src: url },
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
                onClick={() => handlePlay(i, entry)}
                disabled={
                  (!entry.stream.url && !entry.stream.externalUrl) || pendingProbeIdx === i
                }
                className="flex items-center gap-1 rounded-md bg-slate-800 px-3 py-1.5 text-xs text-slate-200 hover:bg-primary disabled:opacity-40"
              >
                {pendingProbeIdx === i ? (
                  <>
                    <Loader2 className="h-3 w-3 animate-spin" /> Probing…
                  </>
                ) : entry.stream.url ? (
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

      {probeWarning && (
        <div className="fixed inset-0 z-30 flex items-center justify-center bg-black/70 p-4">
          <div className="max-w-md space-y-4 rounded-2xl border border-amber-500/40 bg-slate-900 p-6">
            <div className="flex items-center gap-2 text-amber-300">
              <AlertTriangle className="h-5 w-5" />
              <h2 className="text-lg font-semibold">Stream may not play in your browser</h2>
            </div>
            <p className="text-sm text-slate-300">{probeWarning.reason}</p>
            <p className="text-xs text-slate-500">
              The OmnioTV Android app handles this format natively. In the browser, you can try
              anyway — we'll fall back to the next stream if it fails.
            </p>
            <div className="flex gap-2 pt-2">
              <button
                type="button"
                onClick={() => setProbeWarning(null)}
                className="flex-1 rounded-lg border border-slate-700 px-4 py-2 text-sm text-slate-200 hover:border-primary"
              >
                Pick another
              </button>
              <button
                type="button"
                onClick={playAnyway}
                className="flex-1 rounded-lg bg-amber-500/80 px-4 py-2 text-sm font-medium text-slate-950 hover:bg-amber-400"
              >
                Play anyway
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function probeIssue(probe: {
  contentType: string | null;
  isMkv: boolean;
  isHls: boolean;
  isDash: boolean;
  isMp4: boolean;
}): string | null {
  if (probe.isMkv) {
    return "This stream is in MKV format, which most browsers cannot play without remuxing.";
  }
  if (probe.contentType && /audio\/(ac3|eac3|dts|truehd)/i.test(probe.contentType)) {
    return "This stream uses an audio codec (Dolby AC3/EAC3/DTS/TrueHD) browsers don't support.";
  }
  if (!probe.isHls && !probe.isDash && !probe.isMp4 && probe.contentType) {
    return `Unrecognized media type "${probe.contentType}" — browser playback is unlikely.`;
  }
  return null;
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
