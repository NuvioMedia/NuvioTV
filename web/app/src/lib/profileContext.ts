// Tiny helper around the active profile id. The profile is part of the URL
// (`/p/$profileId/...`) so this just extracts/coerces it.

export function parseProfileId(raw: string | undefined): number {
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 1) return 1;
  return Math.floor(n);
}
