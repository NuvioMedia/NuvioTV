// Browser codec compatibility scoring. Used by the stream picker to surface
// playable streams first and to label incompatible ones. Mirrors the Phase-3
// transcode trigger: anything that scores `incompatible` would need ffmpeg.

export type Compatibility = "native" | "likely" | "needsTranscode" | "unknown";

export interface StreamHints {
  filename?: string;
  url?: string;
  title?: string;
  description?: string;
}

interface Signal {
  re: RegExp;
  // What this signal implies about playability.
  weight: -3 | -2 | -1 | 0 | 1 | 2;
}

const SIGNALS: Signal[] = [
  // Containers — MKV cannot play in MSE without remux.
  { re: /\.mkv\b/i, weight: -2 },
  { re: /\.avi\b/i, weight: -3 },
  { re: /\.ts\b/i, weight: -1 },
  { re: /\.mp4\b/i, weight: 2 },
  { re: /\.m3u8\b/i, weight: 2 },
  { re: /\.mpd\b/i, weight: 2 },
  { re: /\.webm\b/i, weight: 2 },

  // Audio codecs — AC3/EAC3/DTS/TrueHD/Atmos are not licensed in browsers.
  { re: /\b(ac3|dd5|dd\.5|dolby\.digital)\b/i, weight: -2 },
  { re: /\b(eac3|dd\+|ddp|dolby\.digital\.plus)\b/i, weight: -2 },
  { re: /\b(dts(-?hd)?|dts\.x|dts-?ma)\b/i, weight: -3 },
  { re: /\b(truehd|atmos|mlp)\b/i, weight: -3 },
  { re: /\b(aac|opus|mp3)\b/i, weight: 1 },

  // Video codecs — HEVC fails on Chrome/FF. AV1/H.264 are fine on modern browsers.
  { re: /\b(x265|h265|hevc)\b/i, weight: -1 },
  { re: /\b(x264|h264|avc)\b/i, weight: 1 },
  { re: /\b(av1)\b/i, weight: 1 },
  { re: /\b(vc-?1)\b/i, weight: -2 },
];

export function scoreStream(hints: StreamHints): {
  score: number;
  compatibility: Compatibility;
  reasons: string[];
} {
  const haystack = [hints.filename, hints.url, hints.title, hints.description]
    .filter(Boolean)
    .join(" ");
  if (!haystack) return { score: 0, compatibility: "unknown", reasons: [] };

  let score = 0;
  const reasons: string[] = [];
  for (const sig of SIGNALS) {
    if (sig.re.test(haystack)) {
      score += sig.weight;
      reasons.push(`${sig.weight > 0 ? "+" : ""}${sig.weight} ${sig.re.source}`);
    }
  }

  let compatibility: Compatibility;
  if (score >= 2) compatibility = "native";
  else if (score >= 0) compatibility = "likely";
  else if (score >= -2) compatibility = "needsTranscode";
  else compatibility = "needsTranscode";

  return { score, compatibility, reasons };
}

// Runtime probe — best-effort capability check using browser APIs.
export function browserCanPlayMime(mime: string): boolean {
  if (typeof window === "undefined") return false;
  try {
    if (typeof MediaSource !== "undefined" && MediaSource.isTypeSupported(mime)) return true;
  } catch {
    // ignore
  }
  const v = document.createElement("video");
  return v.canPlayType(mime) !== "";
}

export function pickBestStreams<T extends StreamHints>(streams: T[]): T[] {
  return [...streams]
    .map((s) => ({ stream: s, ...scoreStream(s) }))
    .sort((a, b) => b.score - a.score)
    .map((entry) => entry.stream);
}
