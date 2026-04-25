"use client";

import { AlertTriangle, Check, Loader2 } from "lucide-react";

export type SaveState =
  | { kind: "idle" }
  | { kind: "saving" }
  | { kind: "saved"; at: number }
  | { kind: "error"; message: string }
  | { kind: "conflict" };

interface Props {
  dirty: boolean;
  state: SaveState;
  onSave: () => void;
  onDiscard: () => void;
  // Disable the save button when local validation fails.
  disabled?: boolean;
}

export function SaveBar({ dirty, state, onSave, onDiscard, disabled }: Props) {
  return (
    <div className="sticky bottom-0 z-10 mt-6 -mx-4 border-t border-slate-700/50 bg-slate-900/80 px-4 py-3 backdrop-blur sm:-mx-6 sm:px-6">
      <div className="flex items-center gap-3">
        <Status state={state} dirty={dirty} />
        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            onClick={onDiscard}
            disabled={!dirty || state.kind === "saving"}
            className="rounded-lg border border-slate-700 px-3 py-1.5 text-sm text-slate-300 transition hover:border-slate-600 disabled:cursor-not-allowed disabled:opacity-40"
          >
            Discard
          </button>
          <button
            type="button"
            onClick={onSave}
            disabled={!dirty || state.kind === "saving" || disabled}
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-white transition hover:bg-primary/80 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {state.kind === "saving" ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}

function Status({ state, dirty }: { state: SaveState; dirty: boolean }) {
  if (state.kind === "saving") {
    return (
      <span className="inline-flex items-center gap-2 text-xs text-slate-400">
        <Loader2 className="h-3 w-3 animate-spin" /> Saving…
      </span>
    );
  }
  if (state.kind === "saved") {
    return (
      <span className="inline-flex items-center gap-2 text-xs text-emerald-300">
        <Check className="h-3 w-3" /> Saved
      </span>
    );
  }
  if (state.kind === "error") {
    return (
      <span className="inline-flex items-center gap-2 text-xs text-rose-300">
        <AlertTriangle className="h-3 w-3" /> {state.message}
      </span>
    );
  }
  if (state.kind === "conflict") {
    return (
      <span className="inline-flex items-center gap-2 text-xs text-amber-300">
        <AlertTriangle className="h-3 w-3" /> The TV updated this feature
        while you were editing. The page has been refreshed — re-apply your
        changes and save again.
      </span>
    );
  }
  if (dirty) {
    return <span className="text-xs text-slate-400">Unsaved changes</span>;
  }
  return <span className="text-xs text-slate-500">No changes</span>;
}
