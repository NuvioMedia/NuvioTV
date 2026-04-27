import { create } from "zustand";

export interface PlayerSnapshot {
  position: number;
  duration: number;
  paused: boolean;
}

interface PlayerStore {
  snapshot: PlayerSnapshot;
  setSnapshot: (s: Partial<PlayerSnapshot>) => void;
  reset: () => void;
}

const initial: PlayerSnapshot = { position: 0, duration: 0, paused: true };

export const usePlayerStore = create<PlayerStore>((set) => ({
  snapshot: initial,
  setSnapshot: (s) => set((prev) => ({ snapshot: { ...prev.snapshot, ...s } })),
  reset: () => set({ snapshot: initial }),
}));
