import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";
import {
  themeSettingsSchema,
  type ThemeSettings,
} from "@/lib/settings/schemas";
import ThemeSettingsForm from "@/components/forms/ThemeSettingsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function ThemeSettingsPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const snap = await getSettingsSnapshot(id);

  const parsed = themeSettingsSchema.safeParse(
    snap.features.theme_settings ?? {}
  );
  const initial: ThemeSettings = parsed.success ? parsed.data : {};

  return (
    <div className="space-y-6">
      <Link
        href={`/p/${id}/settings`}
        className="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-slate-200"
      >
        <ChevronLeft className="h-3 w-3" /> Back to settings
      </Link>
      <header>
        <h1 className="text-2xl font-semibold">Theme</h1>
        <p className="text-sm text-slate-400">Color palette and typeface for the TV UI.</p>
      </header>
      <ThemeSettingsForm
        profileId={id}
        initial={initial}
        expectedUpdatedAt={snap.updatedAt}
      />
    </div>
  );
}
