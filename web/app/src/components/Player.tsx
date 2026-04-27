import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import shaka from "shaka-player/dist/shaka-player.compiled";
import { recordWatchedItem, upsertWatchProgress } from "@/lib/watchProgress";
import { scrobbleStart, scrobblePause, scrobbleStop } from "@/lib/trakt";
import {
  loadEmbyCreds,
  findItemByImdb,
  reportPlaying,
  reportProgress,
  reportStopped,
  type EmbyCreds,
} from "@/lib/emby";
import { TrackMenu } from "./TrackMenu";
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
}: PlayerProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const [hlsInstance, setHlsInstance] = useState<Hls | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const watchedReportedRef = useRef(false);
  const lastProgressWriteRef = useRef(0);
  const scrobbleStateRef = useRef<"idle" | "started" | "paused">("idle");
  const embyRef = useRef<{ creds: EmbyCreds; itemId: string } | null>(null);
  const lastEmbyProgressRef = useRef(0);

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

  // Resolve an Emby item id once per content. If the user hasn't configured
  // Emby for this profile, or the item isn't on their server, we silently skip.
  useEffect(() => {
    let cancelled = false;
    embyRef.current = null;

    void (async () => {
      const creds = await loadEmbyCreds(profileId).catch(() => null);
      if (!creds || cancelled) return;
      const item = await findItemByImdb(creds, contentId, season, episode).catch(() => null);
      if (!item || cancelled) return;
      embyRef.current = { creds, itemId: item.Id };
    })();

    return () => {
      cancelled = true;
      const ref = embyRef.current;
      const video = videoRef.current;
      if (ref && video) {
        const positionMs = Math.floor(video.currentTime * 1000);
        void reportStopped(ref.creds, { itemId: ref.itemId, positionMs });
      }
      embyRef.current = null;
    };
  }, [profileId, contentId, season, episode]);

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

    function embyArgs() {
      if (!video) return null;
      const ref = embyRef.current;
      if (!ref) return null;
      return {
        creds: ref.creds,
        itemId: ref.itemId,
        positionMs: Math.floor(video.currentTime * 1000),
      };
    }

    function onTimeUpdate() {
      writeProgress();
      // Emby progress reports every 10s — Emby clients typically tick at this rate.
      const now = Date.now();
      if (now - lastEmbyProgressRef.current >= 10_000) {
        lastEmbyProgressRef.current = now;
        const args = embyArgs();
        if (args) {
          void reportProgress(args.creds, {
            itemId: args.itemId,
            positionMs: args.positionMs,
            isPaused: !!video?.paused,
          });
        }
      }
    }
    function onPause() {
      writeProgress();
      if (scrobbleStateRef.current === "started") {
        scrobbleStateRef.current = "paused";
        void scrobblePause(scrobbleArgs()).catch((e) => console.warn("trakt pause", e));
      }
      const args = embyArgs();
      if (args) {
        void reportProgress(args.creds, { ...args, isPaused: true });
      }
    }
    function onPlay() {
      if (scrobbleStateRef.current !== "started") {
        scrobbleStateRef.current = "started";
        void scrobbleStart(scrobbleArgs()).catch((e) => console.warn("trakt start", e));
      }
      const args = embyArgs();
      if (args) {
        void reportPlaying(args.creds, args);
      }
    }
    function onEnded() {
      if (scrobbleStateRef.current !== "idle") {
        const args = scrobbleArgs();
        // Trakt marks watched at >= 80%; we report the actual progress.
        scrobbleStateRef.current = "idle";
        void scrobbleStop(args).catch((e) => console.warn("trakt stop", e));
      }
      const args = embyArgs();
      if (args) void reportStopped(args.creds, args);
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
    <div className="relative h-full w-full">
      <video
        ref={videoRef}
        controls
        autoPlay
        playsInline
        crossOrigin="anonymous"
        className="h-full w-full bg-black"
      />
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
