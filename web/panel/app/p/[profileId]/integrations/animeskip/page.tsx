import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import {
  animeskipSettingsSchema,
  type AnimeskipSettings,
} from "@/lib/settings/schemas";
import AnimeskipSettingsForm from "@/components/forms/AnimeskipSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function AnimeskipPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const parsed = animeskipSettingsSchema.safeParse(
    snap.features.animeskip_settings ?? {}
  );
  const initial: AnimeskipSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/integrations`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to integrations
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">AnimeSkip</h1>
        <p className="text-sm text-slate-400">
          Auto-skip intros and outros for anime episodes.
        </p>
      </header>
      <AnimeskipSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
