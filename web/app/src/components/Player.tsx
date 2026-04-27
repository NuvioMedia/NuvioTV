import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import shaka from "shaka-player/dist/shaka-player.compiled";
import { recordWatchedItem, upsertWatchProgress } from "@/lib/watchProgress";
import { scrobbleStart, scrobblePause, scrobbleStop } from "@/lib/trakt";
import { loadEmbyCreds, EmbySession } from "@/lib/emby";
import { TrackMenu } from "./TrackMenu";
import { PlayerControls } from "./PlayerControls";
import { PlayerGestures } from "./PlayerGestures";
import { Subtitles, type SubtitleSource } from "./Subtitles";

interface PlayerProps {
  src: string;
  profileId: number;
  contentId: string;
  contentType: string;
  videoId: string;
  season: number | null;
  episode: number | null;
  initialPosition?: number;
  subtitles?: SubtitleSource[];
  // Set when the chosen stream came from the user's Emby server. The player
  // uses this to spin up a Now-Playing session against Emby. Skipped entirely
  // for non-Emby streams — Emby would have nothing to scrobble against.
  emby?: { itemId: string; mediaSourceId: string };
}

const PROGRESS_INTERVAL_MS = 5_000;
const WATCHED_THRESHOLD = 0.85;

export function Player({
  src,
  profileId,
  contentId,
  contentType,
  videoId,
  season,
  episode,
  initialPosition = 0,
  subtitles = [],
  emby,
}: PlayerProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const [hlsInstance, setHlsInstance] = useState<Hls | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const watchedReportedRef = useRef(false);
  const lastProgressWriteRef = useRef(0);
  const scrobbleStateRef = useRef<"idle" | "started" | "paused">("idle");
  const embySessionRef = useRef<EmbySession | null>(null);

  // Source attach. Three paths:
  //   - DASH (.mpd) → shaka-player
  //   - HLS (.m3u8) → native on Safari, hls.js elsewhere
  //   - everything else (MP4 etc) → set src directly
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    setError(null);
    setReady(false);

    const isHls = /\.m3u8(\?|$)/i.test(src);
    const isDash = /\.mpd(\?|$)/i.test(src);
    let hls: Hls | null = null;
    let shakaPlayer: shaka.Player | null = null;

    if (isDash) {
      shakaPlayer = new shaka.Player();
      shakaPlayer
        .attach(video)
        .then(() => shakaPlayer!.load(src))
        .catch((e: shaka.util.Error) => {
          setError(`DASH error: ${e.message ?? e.code}`);
        });
    } else if (isHls && Hls.isSupported() && !video.canPlayType("application/vnd.apple.mpegurl")) {
      hls = new Hls({
        lowLatencyMode: false,
        backBufferLength: 60,
      });
      hls.on(Hls.Events.ERROR, (_evt, data) => {
        if (data.fatal) {
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) hls?.startLoad();
          else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) hls?.recoverMediaError();
          else setError(`HLS fatal: ${data.details}`);
        }
      });
      hls.attachMedia(video);
      hls.loadSource(src);
      hlsRef.current = hls;
      setHlsInstance(hls);
    } else {
      video.src = src;
      hlsRef.current = null;
      setHlsInstance(null);
    }

    if (initialPosition > 0) {
      video.currentTime = initialPosition;
    }

    return () => {
      if (hls) {
        hls.destroy();
      }
      if (shakaPlayer) {
        void shakaPlayer.destroy();
      }
      hlsRef.current = null;
      setHlsInstance(null);
      video.removeAttribute("src");
      video.load();
    };
  }, [src, initialPosition]);

  // Spin up an Emby session only when the chosen stream actually came from the
  // user's Emby server. Mirrors the Android `isCurrentStreamFromEmbyProvider`
  // gate — reporting against Emby for, say, a torrent stream produces empty
  // sessions with no usable activity-log entry.
  useEffect(() => {
    let cancelled = false;
    embySessionRef.current = null;

    if (!emby) return;

    void (async () => {
      const creds = await loadEmbyCreds(profileId).catch(() => null);
      if (!creds || cancelled) return;
      embySessionRef.current = new EmbySession(creds);
    })();

    return () => {
      cancelled = true;
      const session = embySessionRef.current;
      const video = videoRef.current;
      if (session) {
        const positionMs = video ? Math.floor(video.currentTime * 1000) : 0;
        // reportStop uses keepalive: true internally so it survives the route
        // unmount → page-unload race.
        session.reportStop(positionMs);
      }
      embySessionRef.current = null;
    };
  }, [profileId, emby]);

  // Watch-progress sync. We debounce writes via PROGRESS_INTERVAL_MS so the
  // user's moving timeline doesn't generate a row per second.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    function writeProgress() {
      if (!video) return;
      if (!Number.isFinite(video.currentTime) || !Number.isFinite(video.duration)) return;
      const now = Date.now();
      if (now - lastProgressWriteRef.current < PROGRESS_INTERVAL_MS) return;
      lastProgressWriteRef.current = now;

      void upsertWatchProgress({
        profileId,
        contentId,
        contentType,
        videoId,
        season,
        episode,
        position: Math.floor(video.currentTime * 1000),
        duration: Math.floor(video.duration * 1000),
      });

      const ratio = video.duration > 0 ? video.currentTime / video.duration : 0;
      if (!watchedReportedRef.current && ratio >= WATCHED_THRESHOLD) {
        watchedReportedRef.current = true;
        void recordWatchedItem({
          profileId,
          contentId,
          contentType,
          title: contentId,
          season,
          episode,
        });
      }
    }

    function progressPct(): number {
      if (!video) return 0;
      if (!Number.isFinite(video.duration) || video.duration <= 0) return 0;
      return (video.currentTime / video.duration) * 100;
    }

    function scrobbleArgs() {
      // Trakt only accepts IMDb-style ids. Stremio addons usually use them directly.
      return {
        profileId,
        contentType,
        imdbId: contentId,
        season,
        episode,
        progressPct: progressPct(),
      };
    }

    function currentPositionMs(): number {
      return video ? Math.floor(video.currentTime * 1000) : 0;
    }

    function onTimeUpdate() {
      writeProgress();
      // Emby progress is throttled to 10s inside EmbySession.reportProgress.
      const session = embySessionRef.current;
      if (session && emby) {
        void session.reportProgress(currentPositionMs(), !!video?.paused);
      }
    }
    function onPause() {
      writeProgress();
      if (scrobbleStateRef.current === "started") {
        scrobbleStateRef.current = "paused";
        void scrobblePause(scrobbleArgs()).catch((e) => console.warn("trakt pause", e));
      }
      const session = embySessionRef.current;
      if (session && emby) {
        // Force-flush so the pause shows up immediately in Now Playing
        // instead of waiting for the next 10s tick.
        void session.reportProgress(currentPositionMs(), true, true);
      }
    }
    function onPlay() {
      if (scrobbleStateRef.current !== "started") {
        scrobbleStateRef.current = "started";
        void scrobbleStart(scrobbleArgs()).catch((e) => console.warn("trakt start", e));
      }
      const session = embySessionRef.current;
      if (session && emby) {
        void session.reportStart(emby.itemId, emby.mediaSourceId, currentPositionMs());
      }
    }
    function onEnded() {
      if (scrobbleStateRef.current !== "idle") {
        const args = scrobbleArgs();
        // Trakt marks watched at >= 80%; we report the actual progress.
        scrobbleStateRef.current = "idle";
        void scrobbleStop(args).catch((e) => console.warn("trakt stop", e));
      }
      const session = embySessionRef.current;
      if (session) {
        session.reportStop(currentPositionMs());
      }
    }
    function onLoadedMetadata() {
      setReady(true);
    }

    video.addEventListener("timeupdate", onTimeUpdate);
    video.addEventListener("pause", onPause);
    video.addEventListener("play", onPlay);
    video.addEventListener("ended", onEnded);
    video.addEventListener("loadedmetadata", onLoadedMetadata);
    return () => {
      video.removeEventListener("timeupdate", onTimeUpdate);
      video.removeEventListener("pause", onPause);
      video.removeEventListener("play", onPlay);
      video.removeEventListener("ended", onEnded);
      video.removeEventListener("loadedmetadata", onLoadedMetadata);
      writeProgress();
      if (scrobbleStateRef.current !== "idle") {
        const args = scrobbleArgs();
        scrobbleStateRef.current = "idle";
        void scrobbleStop(args).catch((e) => console.warn("trakt stop on unmount", e));
      }
    };
  }, [profileId, contentId, contentType, videoId, season, episode]);

  return (
    <div ref={containerRef} className="relative h-full w-full">
      <video
        ref={videoRef}
        controls
        autoPlay
        playsInline
        crossOrigin="anonymous"
        className="h-full w-full bg-black"
      />
      <PlayerGestures video={videoRef.current} />
      <PlayerControls video={videoRef.current} containerEl={containerRef.current} />
      <TrackMenu video={videoRef.current} hls={hlsInstance} />
      <Subtitles video={videoRef.current} candidates={subtitles} />
      {!ready && !error && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-slate-500">
          Loading…
        </div>
      )}
      {error && (
        <div className="absolute inset-x-0 bottom-12 mx-auto max-w-md rounded-lg border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-200">
          {error}
        </div>
      )}
    </div>
  );
}
