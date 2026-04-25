import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import { tmdbSettingsSchema, type TmdbSettings } from "@/lib/settings/schemas";
import TmdbSettingsForm from "@/components/forms/TmdbSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function TmdbIntegrationPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  // Decoded values for tmdb_settings (or {} if the TV hasn't synced this
  // profile yet). Re-validate against the Zod schema so a malformed value
  // from disk surfaces an error instead of being passed through to the form.
  const rawDecoded = snap.features.tmdb_settings ?? {};
  const parsed = tmdbSettingsSchema.safeParse(rawDecoded);
  const initial: TmdbSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/integrations`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to integrations
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">TMDB</h1>
        <p className="text-sm text-slate-400">
          Artwork, episode metadata, more like this. Saved changes sync to all TVs
          on this account.
        </p>
      </header>

      {!parsed.success && (
        <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
          The stored TMDB settings did not match the panel&apos;s schema —
          showing defaults. Save to overwrite.
        </div>
      )}

      <TmdbSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
