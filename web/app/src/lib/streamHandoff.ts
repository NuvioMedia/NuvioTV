import type { SubtitleSource } from "@/components/Subtitles";

// Handoff state from the stream picker → player route. URL search params don't
// scale to subtitle arrays; sessionStorage keyed by content_id keeps the player
// route URL shareable without losing the addon-supplied subs.

interface HandoffPayload {
  src: string;
  subtitles: SubtitleSource[];
  // Where the user came from, so the player Back button can return correctly.
  detailId: string;
}

function key(contentType: string, contentId: string): string {
  return `omnio.handoff.${contentType}:${contentId}`;
}

export function saveHandoff(
  contentType: string,
  contentId: string,
  payload: HandoffPayload
): void {
  try {
    sessionStorage.setItem(key(contentType, contentId), JSON.stringify(payload));
  } catch {
    // sessionStorage may be disabled in incognito or full; the URL src still works.
  }
}

export function readHandoff(contentType: string, contentId: string): HandoffPayload | null {
  try {
    const raw = sessionStorage.getItem(key(contentType, contentId));
    if (!raw) return null;
    return JSON.parse(raw) as HandoffPayload;
  } catch {
    return null;
  }
}
