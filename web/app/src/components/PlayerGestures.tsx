import { useEffect, useRef, useState } from "react";
import { FastForward, Rewind } from "lucide-react";

interface PlayerGesturesProps {
  video: HTMLVideoElement | null;
}

const DOUBLE_TAP_MS = 300;
const SEEK_AMOUNT_S = 10;

interface SeekHint {
  side: "left" | "right";
  amount: number;
  ts: number;
}

// Touch-only overlay that captures taps on the video. On mobile, the native
// <video controls> bar takes most of the bottom strip but the upper-middle
// region is dead space — we put gesture handling there:
//   single tap   → toggle play/pause
//   double tap   → seek -10s (left half) / +10s (right half)
// We render a transparent layer above the video but BELOW the other overlay
// buttons (PlayerControls, TrackMenu) so those still respond to clicks.
export function PlayerGestures({ video }: PlayerGesturesProps) {
  const lastTapRef = useRef<{ ts: number; side: "left" | "right" } | null>(null);
  const seekTimerRef = useRef<number | null>(null);
  const [hint, setHint] = useState<SeekHint | null>(null);

  useEffect(() => {
    return () => {
      if (seekTimerRef.current) {
        window.clearTimeout(seekTimerRef.current);
      }
    };
  }, []);

  // Skip rendering on devices that probably aren't touch — pointer-coarse media
  // query is the right gate. On desktop the user has the controls bar already.
  const [isTouch, setIsTouch] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined") return;
    const mq = window.matchMedia("(pointer: coarse)");
    const update = () => setIsTouch(mq.matches);
    update();
    mq.addEventListener?.("change", update);
    return () => mq.removeEventListener?.("change", update);
  }, []);

  function handleTap(e: React.MouseEvent<HTMLDivElement>) {
    if (!video) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const side: "left" | "right" = x < rect.width / 2 ? "left" : "right";
    const now = Date.now();

    const last = lastTapRef.current;
    if (last && now - last.ts < DOUBLE_TAP_MS && last.side === side) {
      // Double tap on the same side: seek.
      lastTapRef.current = null;
      const delta = side === "left" ? -SEEK_AMOUNT_S : SEEK_AMOUNT_S;
      const target = Math.max(0, Math.min(video.duration || 0, video.currentTime + delta));
      video.currentTime = target;
      setHint({ side, amount: SEEK_AMOUNT_S, ts: now });
      if (seekTimerRef.current) window.clearTimeout(seekTimerRef.current);
      seekTimerRef.current = window.setTimeout(() => setHint(null), 600);
      return;
    }

    // First tap — wait for a possible second.
    lastTapRef.current = { ts: now, side };
    window.setTimeout(() => {
      const cur = lastTapRef.current;
      if (cur && cur.ts === now) {
        // No double-tap landed within the window — treat as a single tap.
        lastTapRef.current = null;
        if (video.paused) {
          void video.play().catch(() => {});
        } else {
          video.pause();
        }
      }
    }, DOUBLE_TAP_MS + 20);
  }

  if (!isTouch) return null;

  return (
    <>
      {/* Transparent tap layer. inset-x-0 covers the full width; bottom-20 leaves
          ~80px clear so the native controls bar stays interactive. */}
      <div
        className="absolute inset-x-0 top-0 z-[5] cursor-pointer"
        style={{ bottom: "80px" }}
        onClick={handleTap}
      />
      {hint && (
        <div
          className="pointer-events-none absolute top-1/2 z-10 flex -translate-y-1/2 items-center gap-2 rounded-full bg-black/70 px-4 py-3 text-white backdrop-blur"
          style={{
            left: hint.side === "left" ? "20%" : undefined,
            right: hint.side === "right" ? "20%" : undefined,
          }}
        >
          {hint.side === "left" ? (
            <Rewind className="h-5 w-5" />
          ) : (
            <FastForward className="h-5 w-5" />
          )}
          <span className="text-sm font-medium">{hint.amount}s</span>
        </div>
      )}
    </>
  );
}
