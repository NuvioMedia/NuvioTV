import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import {
  playerSettingsSchema,
  type PlayerSettings,
} from "@/lib/settings/schemas";
import PlayerSettingsForm from "@/components/forms/PlayerSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function PlayerSettingsPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const parsed = playerSettingsSchema.safeParse(
    snap.features.player_settings ?? {}
  );
  const initial: PlayerSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/settings`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to settings
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">Player</h1>
        <p className="text-sm text-slate-400">
          Engine, audio, subtitles, overlays, auto-play, and buffer tuning. Power-user
          territory — most defaults are fine.
        </p>
      </header>
      {!parsed.success && (
        <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
          Stored player settings did not match the schema — showing defaults. Save
          to overwrite.
        </div>
      )}
      <PlayerSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
