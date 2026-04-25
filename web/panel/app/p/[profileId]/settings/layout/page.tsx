import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import {
  layoutSettingsSchema,
  type LayoutSettings,
} from "@/lib/settings/schemas";
import LayoutSettingsForm from "@/components/forms/LayoutSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function LayoutSettingsPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const parsed = layoutSettingsSchema.safeParse(
    snap.features.layout_settings ?? {}
  );
  const initial: LayoutSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/settings`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to settings
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">Layout</h1>
        <p className="text-sm text-slate-400">
          Sidebar, posters, hero rows, and detail-page behaviour. Catalog ordering
          uses raw Gson JSON — reorder via the TV settings UI for safety.
        </p>
      </header>
      {!parsed.success && (
        <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
          Stored layout settings did not match the schema — showing defaults.
        </div>
      )}
      <LayoutSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
