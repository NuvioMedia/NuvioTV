import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import { mdblistSettingsSchema, type MdblistSettings } from "@/lib/settings/schemas";
import MdblistSettingsForm from "@/components/forms/MdblistSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function MdblistIntegrationPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const rawDecoded = snap.features.mdblist_settings ?? {};
  const parsed = mdblistSettingsSchema.safeParse(rawDecoded);
  const initial: MdblistSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/integrations`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to integrations
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">MDBList</h1>
        <p className="text-sm text-slate-400">
          Aggregate ratings from IMDb, Trakt, Letterboxd, Rotten Tomatoes, and
          Metacritic.
        </p>
      </header>

      {!parsed.success && (
        <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
          The stored MDBList settings did not match the panel&apos;s schema —
          showing defaults. Save to overwrite.
        </div>
      )}

      <MdblistSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
