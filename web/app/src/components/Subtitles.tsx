import { useEffect, useRef, useState } from "react";
import JASSUB from "jassub";
import jassubWorkerUrl from "jassub/dist/jassub-worker.js?url";
import jassubWasmUrl from "jassub/dist/jassub-worker.wasm?url";
import jassubModernWasmUrl from "jassub/dist/jassub-worker-modern.wasm?url";
import { Subtitles as SubtitlesIcon, Upload, X } from "lucide-react";
import { PROXY_URL } from "@/lib/proxy";

export interface SubtitleSource {
  url?: string;
  lang: string;
  // For local files dropped by the user.
  text?: string;
}

interface SubtitlesProps {
  video: HTMLVideoElement | null;
  candidates: SubtitleSource[];
}

// Returns true for files that need libass via JASSUB. SRT/VTT can use the
// browser's native <track> renderer; we only burn JASSUB CPU on ASS/SSA.
function needsJassub(url: string | undefined, content: string | undefined): boolean {
  if (url) {
    if (/\.(ass|ssa)(\?|$)/i.test(url)) return true;
    if (/\.(srt|vtt)(\?|$)/i.test(url)) return false;
  }
  if (content) {
    return /^\s*\[script info\]/im.test(content);
  }
  return false;
}

async function fetchSubtitleText(url: string): Promise<string> {
  // Subtitle hosts often miss CORS — route through the proxy.
  const proxied = `${PROXY_URL}?url=${encodeURIComponent(url)}`;
  const response = await fetch(proxied);
  if (!response.ok) throw new Error(`subtitle fetch failed: ${response.status}`);
  return response.text();
}

function srtToVtt(srt: string): string {
  // Tiny SRT → WebVTT shim. Replaces SRT's `,` separator with VTT's `.` and
  // prepends the WEBVTT header. Sufficient for browser <track> consumption.
  const body = srt.replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, "$1.$2");
  return `WEBVTT\n\n${body}`;
}

export function Subtitles({ video, candidates }: SubtitlesProps) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState<SubtitleSource | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const jassubRef = useRef<JASSUB | null>(null);
  const trackUrlRef = useRef<string | null>(null);

  // Tear down whatever sub renderer is active. Called every time the user
  // changes selection or on unmount.
  function teardown() {
    if (jassubRef.current) {
      jassubRef.current.destroy();
      jassubRef.current = null;
    }
    if (trackUrlRef.current) {
      URL.revokeObjectURL(trackUrlRef.current);
      trackUrlRef.current = null;
    }
    if (video) {
      const tracks = video.textTracks;
      for (let i = 0; i < tracks.length; i++) tracks[i]!.mode = "disabled";
      const trackEls = video.querySelectorAll("track[data-omnio-sub]");
      trackEls.forEach((el) => el.remove());
    }
  }

  useEffect(() => {
    return () => teardown();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function selectCandidate(c: SubtitleSource) {
    if (!video) return;
    setLoading(true);
    setError(null);
    teardown();

    try {
      let content = c.text;
      if (!content && c.url) content = await fetchSubtitleText(c.url);
      if (!content) throw new Error("subtitle has no content");

      if (needsJassub(c.url, content)) {
        // JASSUB renders ASS/SSA via libass-wasm onto a canvas overlaid on the video.
        jassubRef.current = new JASSUB({
          video,
          subContent: content,
          workerUrl: jassubWorkerUrl,
          wasmUrl: jassubWasmUrl,
          modernWasmUrl: jassubModernWasmUrl,
        });
      } else {
        // Browser native track. SRT needs a quick conversion to WebVTT.
        const vtt = /^\s*WEBVTT/.test(content) ? content : srtToVtt(content);
        const blob = new Blob([vtt], { type: "text/vtt" });
        const blobUrl = URL.createObjectURL(blob);
        trackUrlRef.current = blobUrl;

        const track = document.createElement("track");
        track.kind = "subtitles";
        track.label = c.lang;
        track.srclang = c.lang;
        track.src = blobUrl;
        track.default = true;
        track.dataset.omnioSub = "1";
        video.appendChild(track);

        // Mode flips after the textTrack list registers it.
        window.setTimeout(() => {
          const tracks = video.textTracks;
          for (let i = 0; i < tracks.length; i++) {
            tracks[i]!.mode = tracks[i]!.label === c.lang ? "showing" : "disabled";
          }
        }, 50);
      }

      setActive(c);
      setOpen(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function uploadLocal(file: File) {
    const text = await file.text();
    void selectCandidate({ url: file.name, lang: "Local", text });
  }

  return (
    <div
      className="absolute z-10 flex flex-col items-end gap-2"
      style={{
        bottom: "calc(env(safe-area-inset-bottom, 0px) + 130px)",
        right: "max(env(safe-area-inset-right, 0px), 16px)",
      }}
    >
      {error && (
        <div className="rounded-md bg-red-500/30 px-3 py-1 text-xs text-red-100">{error}</div>
      )}
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex h-11 min-w-[44px] items-center gap-1 rounded-full bg-black/60 px-3 text-xs text-white backdrop-blur hover:bg-black/80"
      >
        <SubtitlesIcon className="h-4 w-4" />
        {active ? `CC: ${active.lang}` : "Subtitles"}
      </button>
      {open && (
        <div className="min-w-[220px] overflow-hidden rounded-lg border border-slate-700 bg-slate-900/95 shadow-xl">
          <div className="px-3 py-2 text-[10px] uppercase tracking-wide text-slate-500">
            Available
          </div>
          {candidates.length === 0 && (
            <div className="px-3 py-2 text-xs text-slate-500">No subs offered by the addon.</div>
          )}
          {candidates.map((c, i) => (
            <button
              key={i}
              type="button"
              onClick={() => void selectCandidate(c)}
              disabled={loading}
              className={`flex w-full items-center justify-between px-3 py-2 text-left text-xs ${
                active?.url === c.url ? "bg-primary/20 text-primary" : "text-slate-200 hover:bg-slate-800"
              }`}
            >
              <span className="truncate">{c.lang}</span>
              {active?.url === c.url && <span className="ml-2">●</span>}
            </button>
          ))}
          {active && (
            <button
              type="button"
              onClick={() => {
                teardown();
                setActive(null);
                setOpen(false);
              }}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs text-slate-400 hover:bg-slate-800 hover:text-slate-200"
            >
              <X className="h-3 w-3" /> Turn off
            </button>
          )}
          <label className="flex w-full cursor-pointer items-center gap-2 border-t border-slate-800 px-3 py-2 text-xs text-slate-400 hover:bg-slate-800 hover:text-slate-200">
            <Upload className="h-3 w-3" />
            Upload .srt / .ass
            <input
              type="file"
              accept=".srt,.ass,.ssa,.vtt"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void uploadLocal(file);
              }}
            />
          </label>
        </div>
      )}
    </div>
  );
}
