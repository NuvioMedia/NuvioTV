import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import {
  traktSettingsSchema,
  type TraktSettings,
} from "@/lib/settings/schemas";
import TraktSettingsForm from "@/components/forms/TraktSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function TraktPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const parsed = traktSettingsSchema.safeParse(
    snap.features.trakt_settings ?? {}
  );
  const initial: TraktSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/integrations`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to integrations
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">Trakt</h1>
        <p className="text-sm text-slate-400">
          Watch progress sync, Continue Watching cap, and comment display.
        </p>
      </header>
      <TraktSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
