import { notFound } from "next/navigation";
import { getProfile } from "@/lib/data/profiles";
import DangerZone from "@/components/forms/DangerZone";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function DangerPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const profile = await getProfile(id);
  if (!profile) notFound();

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Danger zone</h1>
        <p className="text-sm text-slate-400">
          Destructive operations. Read each one before clicking.
        </p>
      </header>

      <DangerZone profileId={id} profileName={profile.name} />
    </div>
  );
}
