import { useEffect, useState } from "react";
import { Maximize2, Minimize2, PictureInPicture2 } from "lucide-react";

interface PlayerControlsProps {
  video: HTMLVideoElement | null;
  containerEl: HTMLElement | null;
}

// Top-right cluster of player-level controls (PiP, fullscreen).
// Native <video controls> already exposes scrub/volume; this component just
// adds the bits browsers don't put in the default UI on mobile.
export function PlayerControls({ video, containerEl }: PlayerControlsProps) {
  const [pipSupported, setPipSupported] = useState(false);
  const [pipActive, setPipActive] = useState(false);
  const [fullscreenSupported, setFullscreenSupported] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);

  useEffect(() => {
    setPipSupported(
      typeof document !== "undefined" && "pictureInPictureEnabled" in document
        ? !!document.pictureInPictureEnabled
        : false
    );
    setFullscreenSupported(
      typeof document !== "undefined" && (!!document.fullscreenEnabled || !!(document as DocumentWithVendor).webkitFullscreenEnabled)
    );

    function onPipEnter() {
      setPipActive(true);
    }
    function onPipLeave() {
      setPipActive(false);
    }
    function onFsChange() {
      setFullscreen(!!document.fullscreenElement || !!(document as DocumentWithVendor).webkitFullscreenElement);
    }

    video?.addEventListener("enterpictureinpicture", onPipEnter);
    video?.addEventListener("leavepictureinpicture", onPipLeave);
    document.addEventListener("fullscreenchange", onFsChange);
    document.addEventListener("webkitfullscreenchange", onFsChange);

    return () => {
      video?.removeEventListener("enterpictureinpicture", onPipEnter);
      video?.removeEventListener("leavepictureinpicture", onPipLeave);
      document.removeEventListener("fullscreenchange", onFsChange);
      document.removeEventListener("webkitfullscreenchange", onFsChange);
    };
  }, [video]);

  async function togglePip() {
    if (!video) return;
    try {
      if (pipActive) {
        await document.exitPictureInPicture();
      } else {
        await video.requestPictureInPicture();
      }
    } catch (e) {
      console.warn("PiP toggle failed", e);
    }
  }

  async function toggleFullscreen() {
    const el = containerEl ?? video;
    if (!el) return;
    try {
      if (fullscreen) {
        if (document.exitFullscreen) await document.exitFullscreen();
        else (document as DocumentWithVendor).webkitExitFullscreen?.();
      } else {
        // iOS Safari only allows fullscreen on the <video> element itself,
        // not arbitrary containers; the webkit prefix accepts that.
        if (el.requestFullscreen) {
          await el.requestFullscreen();
        } else if ((el as ElementWithVendor).webkitRequestFullscreen) {
          (el as ElementWithVendor).webkitRequestFullscreen?.();
        } else if (video && (video as VideoWithVendor).webkitEnterFullscreen) {
          // iOS Safari fallback: only the video supports fullscreen.
          (video as VideoWithVendor).webkitEnterFullscreen?.();
        }
      }
    } catch (e) {
      console.warn("fullscreen toggle failed", e);
    }
  }

  if (!pipSupported && !fullscreenSupported) return null;

  return (
    <div
      className="absolute z-10 flex items-center gap-2"
      style={{
        top: "max(env(safe-area-inset-top, 0px), 16px)",
        right: "max(env(safe-area-inset-right, 0px), 16px)",
      }}
    >
      {pipSupported && (
        <button
          type="button"
          onClick={togglePip}
          aria-label={pipActive ? "Exit picture-in-picture" : "Picture-in-picture"}
          className="flex h-11 w-11 items-center justify-center rounded-full bg-black/60 text-white backdrop-blur hover:bg-black/80"
        >
          <PictureInPicture2 className="h-5 w-5" />
        </button>
      )}
      {fullscreenSupported && (
        <button
          type="button"
          onClick={toggleFullscreen}
          aria-label={fullscreen ? "Exit fullscreen" : "Fullscreen"}
          className="flex h-11 w-11 items-center justify-center rounded-full bg-black/60 text-white backdrop-blur hover:bg-black/80"
        >
          {fullscreen ? <Minimize2 className="h-5 w-5" /> : <Maximize2 className="h-5 w-5" />}
        </button>
      )}
    </div>
  );
}

// Vendor-prefix shims for Safari.
interface DocumentWithVendor extends Document {
  webkitFullscreenEnabled?: boolean;
  webkitFullscreenElement?: Element | null;
  webkitExitFullscreen?: () => void;
}

interface ElementWithVendor extends HTMLElement {
  webkitRequestFullscreen?: () => void;
}

interface VideoWithVendor extends HTMLVideoElement {
  webkitEnterFullscreen?: () => void;
}
