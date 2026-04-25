// Shared return shape for all panel Server Actions that mutate Supabase.
// Forms branch on `ok` and `conflict` to decide UI feedback.

export type ActionResult =
  | { ok: true; updatedAt: string | null }
  | { ok: false; conflict: true }
  | { ok: false; conflict?: false; error: string };
