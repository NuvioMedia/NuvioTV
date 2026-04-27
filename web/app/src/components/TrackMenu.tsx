import { useEffect, useState } from "react";
import type Hls from "hls.js";
import { Subtitles, Volume2 } from "lucide-react";

interface AudioOpt {
  id: string;
  label: string;
  active: boolean;
}

interface TextOpt {
  id: string;
  label: string;
  active: boolean;
}

interface QualityOpt {
  id: number; // -1 = auto, otherwise level index in hls.levels
  label: string;
  active: boolean;
}

interface TrackMenuProps {
  video: HTMLVideoElement | null;
  hls: Hls | null;
}

export function TrackMenu({ video, hls }: TrackMenuProps) {
  const [audio, setAudio] = useState<AudioOpt[]>([]);
  const [text, setText] = useState<TextOpt[]>([]);
  const [quality, setQuality] = useState<QualityOpt[]>([]);
  const [open, setOpen] = useState<"audio" | "subs" | "quality" | null>(null);

  // HLS audio tracks (preferred when hls.js owns them) / fallback to native.
  useEffect(() => {
    if (!video) return;

    function refresh() {
      if (hls && hls.audioTracks.length > 0) {
        setAudio(
          hls.audioTracks.map((t, i) => ({
            id: String(i),
            label: t.name || t.lang || `Audio ${i + 1}`,
            active: i === hls.audioTrack,
          }))
        );
      } else if (video && "audioTracks" in video) {
        const tracks = ((video as unknown as { audioTracks?: unknown }).audioTracks ?? []) as ArrayLike<{
          id: string;
          label: string;
          language: string;
          enabled: boolean;
        }>;
        setAudio(
          Array.from({ length: tracks.length }, (_, i) => {
            const t = tracks[i]!;
            return {
              id: t.id || String(i),
              label: t.label || t.language || `Audio ${i + 1}`,
              active: t.enabled,
            };
          })
        );
      } else {
        setAudio([]);
      }

      const textTracks = video?.textTracks ?? null;
      if (textTracks) {
        setText(
          Array.from({ length: textTracks.length }, (_, i) => {
            const t = textTracks[i]!;
            return {
              id: String(i),
              label: t.label || t.language || `Subtitle ${i + 1}`,
              active: t.mode === "showing",
            };
          })
        );
      }

      if (hls) {
        const levels = hls.levels;
        setQuality([
          { id: -1, label: "Auto", active: hls.autoLevelEnabled },
          ...levels.map((lvl, i) => ({
            id: i,
            label: lvl.height ? `${lvl.height}p` : `${Math.round(lvl.bitrate / 1000)} kbps`,
            active: !hls.autoLevelEnabled && hls.currentLevel === i,
          })),
        ]);
      } else {
        setQuality([]);
      }
    }

    refresh();

    const handlers: Array<[string, () => void]> = [
      ["loadedmetadata", refresh],
      ["loadeddata", refresh],
    ];
    handlers.forEach(([ev, fn]) => video.addEventListener(ev, fn));
    const interval = window.setInterval(refresh, 2000);

    return () => {
      handlers.forEach(([ev, fn]) => video.removeEventListener(ev, fn));
      window.clearInterval(interval);
    };
  }, [video, hls]);

  function selectAudio(id: string) {
    if (!video) return;
    if (hls && hls.audioTracks.length > 0) {
      hls.audioTrack = Number(id);
    } else if ("audioTracks" in video) {
      const tracks = (video as unknown as { audioTracks: ArrayLike<{ id: string; enabled: boolean }> & { length: number } }).audioTracks;
      for (let i = 0; i < tracks.length; i++) {
        const t = tracks[i]!;
        t.enabled = t.id === id || String(i) === id;
      }
    }
    setOpen(null);
  }

  function selectText(id: string) {
    if (!video) return;
    const tracks = video.textTracks;
    for (let i = 0; i < tracks.length; i++) {
      tracks[i]!.mode = String(i) === id ? "showing" : "disabled";
    }
    setOpen(null);
  }

  function selectQuality(id: number) {
    if (!hls) return;
    if (id === -1) {
      hls.currentLevel = -1; // back to auto
    } else {
      hls.currentLevel = id;
    }
    setOpen(null);
  }

  if (audio.length === 0 && text.length === 0 && quality.length === 0) return null;

  return (
    <div
      className="absolute z-10 flex items-center gap-2"
      style={{
        bottom: "calc(env(safe-area-inset-bottom, 0px) + 80px)",
        right: "max(env(safe-area-inset-right, 0px), 16px)",
      }}

    >
      {audio.length > 1 && (
        <Pill
          icon={<Volume2 className="h-4 w-4" />}
          label="Audio"
          isOpen={open === "audio"}
          onToggle={() => setOpen(open === "audio" ? null : "audio")}
          options={audio}
          onSelect={(id) => selectAudio(id)}
        />
      )}
      {text.length > 0 && (
        <Pill
          icon={<Subtitles className="h-4 w-4" />}
          label="Subtitles"
          isOpen={open === "subs"}
          onToggle={() => setOpen(open === "subs" ? null : "subs")}
          options={[{ id: "-1", label: "Off", active: !text.some((t) => t.active) }, ...text]}
          onSelect={(id) => {
            if (id === "-1") {
              const tracks = video?.textTracks;
              if (tracks) {
                for (let i = 0; i < tracks.length; i++) tracks[i]!.mode = "disabled";
              }
              setOpen(null);
              return;
            }
            selectText(id);
          }}
        />
      )}
      {quality.length > 1 && (
        <Pill
          label="Quality"
          isOpen={open === "quality"}
          onToggle={() => setOpen(open === "quality" ? null : "quality")}
          options={quality.map((q) => ({ id: String(q.id), label: q.label, active: q.active }))}
          onSelect={(id) => selectQuality(Number(id))}
        />
      )}
    </div>
  );
}

function Pill({
  icon,
  label,
  isOpen,
  onToggle,
  options,
  onSelect,
}: {
  icon?: React.ReactNode;
  label: string;
  isOpen: boolean;
  onToggle: () => void;
  options: { id: string; label: string; active: boolean }[];
  onSelect: (id: string) => void;
}) {
  return (
    <div className="relative">
      <button
        type="button"
        onClick={onToggle}
        className="flex h-11 min-w-[44px] items-center gap-1 rounded-full bg-black/60 px-3 text-xs text-white backdrop-blur hover:bg-black/80"
      >
        {icon}
        {label}
      </button>
      {isOpen && (
        <div className="absolute right-0 top-full mt-2 min-w-[180px] overflow-hidden rounded-lg border border-slate-700 bg-slate-900/95 shadow-xl">
          {options.map((opt) => (
            <button
              key={opt.id}
              type="button"
              onClick={() => onSelect(opt.id)}
              className={`flex w-full items-center justify-between px-3 py-2 text-left text-xs ${
                opt.active ? "bg-primary/20 text-primary" : "text-slate-200 hover:bg-slate-800"
              }`}
            >
              <span className="truncate">{opt.label}</span>
              {opt.active && <span className="ml-2">●</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
