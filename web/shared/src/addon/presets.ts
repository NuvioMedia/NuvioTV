// Default Stremio addons every account gets seeded with — same identity as the
// official Stremio defaults so user URLs from the Android app interoperate.

export const CINEMETA_BASE = "https://v3-cinemeta.strem.io";
export const OPENSUBTITLES_V3_BASE = "https://opensubtitles-v3.strem.io";

// A Phase-1-friendly demo addon list; the real list comes from Supabase per profile.
export const DEMO_ADDONS = [{ url: CINEMETA_BASE, name: "Cinemeta" }];
