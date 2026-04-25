import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import {
  trailerSettingsSchema,
  type TrailerSettings,
} from "@/lib/settings/schemas";
import TrailerSettingsForm from "@/components/forms/TrailerSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function TrailerSettingsPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const parsed = trailerSettingsSchema.safeParse(
    snap.features.trailer_settings ?? {}
  );
  const initial: TrailerSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/settings`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to settings
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">Trailers</h1>
        <p className="text-sm text-slate-400">
          Auto-play YouTube trailers when a poster is focused.
        </p>
      </header>
      <TrailerSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
