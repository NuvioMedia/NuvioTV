import { listAvatarCatalog, listProfiles } from "@/lib/data/profiles";
import ProfilesManager from "@/components/forms/ProfilesManager";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function ManageProfilesPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);

  const [profiles, avatarCatalog] = await Promise.all([
    listProfiles(),
    listAvatarCatalog().catch(() => []),
  ]);

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Manage profiles</h1>
        <p className="text-sm text-slate-400">
          Rename, recolor, change avatars, and toggle whether a sub-profile shares
          addons or plugins with the primary profile. New profiles and PIN
          management are TV-only.
        </p>
      </header>

      <ProfilesManager
        profiles={profiles}
        avatarCatalog={avatarCatalog}
        currentProfileId={id}
      />
    </div>
  );
}
