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
import { useEnabledPlugins } from "@/lib/usePlugins";
import { callPlugin } from "@/plugin/manager";
import { saveHandoff } from "@/lib/streamHandoff";
import { loadEmbyCreds, getEmbyStreams, type EmbyLookupState } from "@/lib/emby";

export const Route = createFileRoute("/p/$profileId/stream/$type/$id")({
  component: StreamPickerPage,
});

// Compatibility tag — adds a fifth value beyond what @omnio/shared/codec ships
// with. Emby streams always go through the user's server, which transparently
// remuxes / transcodes; we surface this as its own badge so the user knows the
// codec problem is solved upstream.
type StreamCompatibility = Compatibility | "embyTranscode";

interface ScoredStream {
  source: string;
  sourceKind: "addon" | "plugin" | "emby";
  stream: AddonStream;
  score: number;
  compatibility: StreamCompatibility;
}

interface StreamFetchResult {
  scored: ScoredStream[];
  embyState: EmbyLookupState;
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
  const { data: plugins = [] } = useEnabledPlugins(profileId);

  const streamQueries = useQuery({
    queryKey: [
      "streams",
      params.type,
      params.id,
      profileId,
      sources.map((s) => s.url),
      plugins.map((p) => p.url),
    ],
    queryFn: async (): Promise<StreamFetchResult> => {
      const [season, episode] = parseSeasonEpisode(params.id);
      const baseId = params.id.split(":")[0]!;

      const addonResults = Promise.allSettled(
        sources.map((src) =>
          fetchStreams(src.url, params.type, params.id, { proxyUrl: PROXY_URL }).then((res) =>
            res.streams.map((s): RawScored => ({
              source: src.name ?? src.url,
              sourceKind: "addon" as const,
              stream: s,
            }))
          )
        )
      );

      // Plugins return raw stream objects; we coerce them to AddonStream shape.
      const pluginResults = Promise.allSettled(
        plugins.map(async (p): Promise<RawScored[]> => {
          const result = await callPlugin(p.url, {
            tmdbId: baseId,
            mediaType: params.type === "series" ? "series" : "movie",
            season: season ?? undefined,
            episode: episode ?? undefined,
          });
          if (!result.ok) {
            console.warn(`plugin ${p.url} failed`, result.error);
            return [];
          }
          return result.streams.map((s) => ({
            source: p.name ?? "Plugin",
            sourceKind: "plugin" as const,
            stream: {
              url: s.url,
              title: s.title ?? s.filename,
              description: s.description,
              behaviorHints: s.filename ? { filename: s.filename } : undefined,
            } as AddonStream,
          }));
        })
      );

      // Emby — read creds from profile_settings, then ask the user's server
      // for MediaSources matching this IMDb id. Empty array if not configured.
      const embyResult = (async (): Promise<{
        scored: RawScored[];
        state: EmbyLookupState;
      }> => {
        const creds = await loadEmbyCreds(profileId).catch(() => null);
        if (!creds) {
          return { scored: [], state: { kind: "no-creds" } };
        }
        const result = await getEmbyStreams(creds, baseId, season, episode);
        const scored = result.streams.map((c): RawScored => ({
          source: "Emby",
          sourceKind: "emby" as const,
          stream: {
            url: c.url,
            title: c.title,
            description: [
              c.videoCodec,
              c.audioCodec,
              c.size ? `${(c.size / 1024 / 1024 / 1024).toFixed(1)} GB` : null,
            ]
              .filter(Boolean)
              .join(" • "),
            behaviorHints: c.filename ? { filename: c.filename } : undefined,
          } as AddonStream,
          embyMeta: { isTranscoding: c.isTranscoding },
        }));
        return { scored, state: result.state };
      })().catch((e: Error) => {
        console.warn("emby lookup failed", e);
        return {
          scored: [] as RawScored[],
          state: {
            kind: "network-error" as const,
            step: "outer",
            message: e.message,
          } satisfies EmbyLookupState,
        };
      });

      const [addonsRes, pluginsRes, embyRes] = await Promise.all([
        addonResults,
        pluginResults,
        embyResult,
      ]);
      const flat: RawScored[] = [
        ...addonsRes.flatMap((r) => (r.status === "fulfilled" ? r.value : [])),
        ...pluginsRes.flatMap((r) => (r.status === "fulfilled" ? r.value : [])),
        ...embyRes.scored,
      ];

      const scored = flat
        .filter((entry) => entry.stream.url || entry.stream.externalUrl)
        .map((entry): ScoredStream => {
          if (entry.sourceKind === "emby") {
            // Emby transcodes server-side, so playability is a non-issue. We
            // give it a strong score so it floats above ambiguous addon entries.
            return {
              source: entry.source,
              sourceKind: entry.sourceKind,
              stream: entry.stream,
              score: 5,
              compatibility: "embyTranscode",
            };
          }
          const { score, compatibility } = scoreStream({
            filename: entry.stream.behaviorHints?.filename,
            url: entry.stream.url,
            title: entry.stream.title,
            description: entry.stream.description,
          });
          return {
            source: entry.source,
            sourceKind: entry.sourceKind,
            stream: entry.stream,
            score,
            compatibility,
          };
        })
        .sort((a, b) => b.score - a.score);

      return { scored, embyState: embyRes.state };
    },
  });

  function navigateToPlayer(s: AddonStream) {
    if (!s.url) return;
    saveHandoff(params.type, params.id, {
      src: s.url,
      subtitles: (s.subtitles ?? []).map((sub) => ({ url: sub.url, lang: sub.lang })),
      detailId: params.id.split(":")[0]!,
    });
    navigate({
      to: "/p/$profileId/player/$type/$id",
      params: { profileId: String(profileId), type: params.type, id: params.id },
      search: { src: s.url },
    });
  }

  async function handlePlay(idx: number, entry: ScoredStream) {
    const s = entry.stream;
    if (!s.url) {
      if (s.externalUrl) window.open(s.externalUrl, "_blank", "noopener");
      return;
    }

    // Skip probe + go straight to player when filename hints already say "native"
    // OR when this is an Emby source (the server already negotiated playback).
    if (entry.compatibility !== "native" && entry.sourceKind !== "emby") {
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

    navigateToPlayer(s);
  }

  function playAnyway() {
    if (!probeWarning?.stream) return;
    const stream = probeWarning.stream;
    setProbeWarning(null);
    navigateToPlayer(stream);
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

      {streamQueries.data && (
        <EmbyDiagnosticsRow
          state={streamQueries.data.embyState}
          hasEmbyStreams={streamQueries.data.scored.some((s) => s.sourceKind === "emby")}
        />
      )}

      {streamQueries.data && streamQueries.data.scored.length === 0 && (
        <div className="rounded-lg border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
          <p className="mb-2 font-medium">No streams found.</p>
          <p className="text-sm text-slate-400">
            Add a stream-providing addon (Torrentio + RealDebrid, OpenSubtitles-Stream, etc.) in the
            panel for this profile.
          </p>
        </div>
      )}

      {streamQueries.data && streamQueries.data.scored.length > 0 && (
        <ul className="divide-y divide-slate-800/60 overflow-hidden rounded-lg border border-slate-800 bg-slate-900/40">
          {streamQueries.data.scored.map((entry, i) => {
            const disabled =
              (!entry.stream.url && !entry.stream.externalUrl) || pendingProbeIdx === i;
            return (
              <li key={i}>
                {/* The whole row is the tap target on mobile so users don't have to hit a tiny "Play" button. */}
                <button
                  type="button"
                  onClick={() => handlePlay(i, entry)}
                  disabled={disabled}
                  className="flex w-full items-center gap-3 p-3 text-left transition hover:bg-slate-800/40 disabled:opacity-40 sm:gap-4 sm:p-3"
                >
                  <CompatibilityBadge value={entry.compatibility} />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm text-slate-200">
                      {entry.stream.title ?? entry.stream.name ?? "Untitled"}
                    </div>
                    <div className="truncate text-xs text-slate-500">
                      {entry.source}
                      {entry.stream.behaviorHints?.filename
                        ? ` • ${entry.stream.behaviorHints.filename}`
                        : ""}
                    </div>
                  </div>
                  <span className="flex h-11 min-w-[44px] items-center gap-1 rounded-md bg-slate-800 px-3 text-xs text-slate-200">
                    {pendingProbeIdx === i ? (
                      <>
                        <Loader2 className="h-4 w-4 animate-spin" /> Probing
                      </>
                    ) : entry.stream.url ? (
                      <>
                        <Play className="h-4 w-4" /> Play
                      </>
                    ) : (
                      <>
                        <ExternalLink className="h-4 w-4" /> Open
                      </>
                    )}
                  </span>
                </button>
              </li>
            );
          })}
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

interface RawScored {
  source: string;
  sourceKind: "addon" | "plugin" | "emby";
  stream: AddonStream;
  embyMeta?: { isTranscoding: boolean };
}

function EmbyDiagnosticsRow({
  state,
  hasEmbyStreams,
}: {
  state: EmbyLookupState;
  hasEmbyStreams: boolean;
}) {
  // The success-with-streams banner — explains transcoding so users pick Emby.
  if (state.kind === "ok" && hasEmbyStreams) {
    return (
      <div className="rounded-lg border border-violet-500/30 bg-violet-500/10 p-3 text-xs text-violet-200">
        <strong className="font-medium">Emby streams transcode at your server.</strong> Codecs
        your browser can't decode (AC3/EAC3/DTS/TrueHD, MKV, HEVC) play fine — your Emby machine
        remuxes/transcodes on the fly to a browser-friendly HLS stream. Pick the Emby row to take
        advantage.
      </div>
    );
  }

  // Silent if Emby simply isn't configured — that's not a problem worth surfacing.
  if (state.kind === "no-creds") return null;

  // Surface every other failure mode so the user can see *why* Emby was empty
  // instead of guessing. Keeps the banner subdued so it doesn't crowd the row.
  let title = "Emby returned no streams";
  let detail: React.ReactNode = null;
  if (state.kind === "not-found") {
    title = `Emby couldn't find this ${state.what}`;
    detail = (
      <>
        Searched for IMDb id <code className="rounded bg-slate-800 px-1">{state.imdbId}</code>.
        Make sure the title is in your Emby library and that its metadata includes the IMDb
        provider id.
      </>
    );
  } else if (state.kind === "no-media-sources") {
    title = `"${state.itemName}" has no playable media on Emby`;
    detail = "The item exists but Emby reported no playable files.";
  } else if (state.kind === "network-error") {
    title = `Emby request failed`;
    detail = (
      <>
        Step <code className="rounded bg-slate-800 px-1">{state.step}</code>: {state.message}.
        Most often this is a CORS rejection or an unreachable server URL — confirm
        <code className="ml-1 rounded bg-slate-800 px-1">web.omnio.tv</code> is allowed in your
        Emby Network → Allowed CORS hosts.
      </>
    );
  }

  return (
    <div className="rounded-lg border border-slate-700/50 bg-slate-900/40 p-3 text-xs text-slate-400">
      <div className="font-medium text-slate-300">{title}</div>
      {detail && <div className="mt-1">{detail}</div>}
    </div>
  );
}

function parseSeasonEpisode(id: string): [number | null, number | null] {
  const parts = id.split(":");
  if (parts.length < 3) return [null, null];
  const season = Number(parts[1]);
  const episode = Number(parts[2]);
  return [
    Number.isFinite(season) ? season : null,
    Number.isFinite(episode) ? episode : null,
  ];
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

function CompatibilityBadge({ value }: { value: StreamCompatibility }) {
  if (value === "embyTranscode")
    return (
      <span
        className="w-24 rounded-full bg-violet-500/20 px-2 py-0.5 text-center text-[10px] uppercase tracking-wide text-violet-300"
        title="Emby server handles transcoding — any codec plays"
      >
        Emby
      </span>
    );
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
