"use client";

import { useEffect } from "react";

// Browser beforeunload prompt — covers tab close / refresh / navigation
// to a different origin. In-app navigation (Next.js Link) does not fire
// beforeunload, so it is the form's job to also display a visible "unsaved
// changes" badge in the SaveBar.
export function useUnsavedWarning(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
}
