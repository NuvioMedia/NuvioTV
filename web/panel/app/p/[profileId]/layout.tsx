import { notFound } from "next/navigation";
import SidebarNav from "@/components/SidebarNav";
import SignOutButton from "@/components/SignOutButton";
import { getProfile, listAvatarCatalog } from "@/lib/data/profiles";

interface Props {
  children: React.ReactNode;
  params: Promise<{ profileId: string }>;
}

export default async function ProfileLayout({ children, params }: Props) {
  const { profileId } = await params;
  const idx = Number.parseInt(profileId, 10);
  if (!Number.isInteger(idx) || idx < 1) notFound();

  const profile = await getProfile(idx);
  if (!profile) notFound();

  const avatar = profile.avatar_id
    ? (await listAvatarCatalog()).find((a) => a.id === profile.avatar_id) ?? null
    : null;

  return (
    <div className="mx-auto flex min-h-screen max-w-6xl gap-6 p-4 sm:p-6">
      <aside className="hidden w-56 shrink-0 sm:block">
        <div className="mb-6 flex items-center gap-3 rounded-xl border border-slate-700/50 bg-slate-800/40 p-3">
          <div
            className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full text-sm font-semibold text-white"
            style={{ backgroundColor: avatar?.bg_color ?? profile.avatar_color_hex }}
          >
            {avatar ? (
              <img
                src={avatar.image_url}
                alt={avatar.display_name}
                className="h-full w-full object-cover"
              />
            ) : profile.name ? (
              profile.name[0]?.toUpperCase()
            ) : (
              profile.profile_index
            )}
          </div>
          <div className="min-w-0">
            <div className="truncate text-sm font-medium text-slate-100">
              {profile.name || `Profile ${profile.profile_index}`}
            </div>
            <div className="text-xs text-slate-400">#{profile.profile_index}</div>
          </div>
        </div>
        <SidebarNav profileIndex={idx} />
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mb-6 flex items-center justify-end">
          <SignOutButton />
        </div>
        {children}
      </main>
    </div>
  );
}
