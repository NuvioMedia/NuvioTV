import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import {
  embyCredentialsSchema,
  type EmbyCredentials,
} from "@/lib/settings/schemas";
import EmbyCredentialsForm from "@/components/forms/EmbyCredentialsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function EmbyPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const parsed = embyCredentialsSchema.safeParse(
    snap.features.emby_credentials ?? {}
  );
  const initial: EmbyCredentials = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/integrations`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to integrations
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">Emby</h1>
        <p className="text-sm text-slate-400">
          Personal Emby server library. Stream from your own server alongside Stremio addons.
        </p>
      </header>
      <EmbyCredentialsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
