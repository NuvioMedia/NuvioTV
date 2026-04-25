import Link from "next/link";
import { ChevronRight, Layout, Palette, Play, Tv2 } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";

interface Props {
  params: Promise<{ profileId: string }>;
}

interface CardSpec {
  href: string;
  title: string;
  blurb: string;
  Icon: typeof Layout;
  preview?: string;
}

export default async function SettingsHubPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const theme = snap.features.theme_settings ?? {};
  const layout = snap.features.layout_settings ?? {};
  const player = snap.features.player_settings ?? {};
  const trailer = snap.features.trailer_settings ?? {};

  const cards: CardSpec[] = [
    {
      href: `/p/${id}/settings/theme`,
      title: "Theme",
      blurb: "Colour palette and typeface.",
      Icon: Palette,
      preview:
        typeof theme.selected_theme === "string"
          ? `${theme.selected_theme}${
              typeof theme.selected_font === "string" ? ` · ${theme.selected_font}` : ""
            }`
          : undefined,
    },
    {
      href: `/p/${id}/settings/layout`,
      title: "Layout",
      blurb: "Sidebar, posters, hero rows.",
      Icon: Layout,
      preview:
        typeof layout.selected_layout === "string"
          ? layout.selected_layout
          : undefined,
    },
    {
      href: `/p/${id}/settings/trailer`,
      title: "Trailers",
      blurb: "Auto-play YouTube trailers on focus.",
      Icon: Tv2,
      preview:
        typeof trailer.trailer_enabled === "boolean"
          ? trailer.trailer_enabled
            ? `on · ${trailer.trailer_delay_seconds ?? 7}s delay`
            : "off"
          : undefined,
    },
    {
      href: `/p/${id}/settings/player`,
      title: "Player",
      blurb: "Engine, audio, subtitles, buffer, auto-play.",
      Icon: Play,
      preview:
        typeof player.player_preference === "string"
          ? `${player.player_preference}${
              typeof player.internal_player_engine === "string"
                ? ` · ${player.internal_player_engine}`
                : ""
            }`
          : undefined,
    },
  ];

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Settings</h1>
        <p className="text-sm text-slate-400">
          Theme, layout, playback, and trailer preferences for this profile. Edits
          sync to all TVs on this account.
        </p>
      </header>

      {snap.unknownFeatureKeys.length > 0 && (
        <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
          <strong className="font-semibold">Unknown feature keys present:</strong>{" "}
          {snap.unknownFeatureKeys.map((k) => (
            <code key={k} className="mr-2 font-mono">
              {k}
            </code>
          ))}
          <p className="mt-1 text-xs text-amber-200/70">
            These were synced by the TV but are not modeled in the panel. Update{" "}
            <code className="mx-1 font-mono">lib/settings/schemas.ts</code> and
            re-deploy to surface them.
          </p>
        </div>
      )}

      <div className="grid gap-3 md:grid-cols-2">
        {cards.map((c) => {
          const Icon = c.Icon;
          return (
            <Link
              key={c.href}
              href={c.href}
              className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5 transition hover:border-primary/60 hover:bg-slate-800/60"
            >
              <div className="mb-2 flex items-center gap-2">
                <Icon className="h-4 w-4 text-slate-400" />
                <h2 className="font-medium text-slate-100">{c.title}</h2>
                <ChevronRight className="ml-auto h-4 w-4 text-slate-500" />
              </div>
              <p className="text-sm text-slate-400">{c.blurb}</p>
              {c.preview && (
                <p className="mt-2 font-mono text-xs text-slate-500">{c.preview}</p>
              )}
            </Link>
          );
        })}
      </div>
    </div>
  );
}
