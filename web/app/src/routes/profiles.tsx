import { createFileRoute, Link, redirect } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Lock, LogOut, User } from "lucide-react";
import type { Profile } from "@omnio/shared/supabase";
import { supabase } from "@/lib/supabase";

export const Route = createFileRoute("/profiles")({
  beforeLoad: async () => {
    const {
      data: { session },
    } = await supabase.auth.getSession();
    if (!session) throw redirect({ to: "/login" });
  },
  component: ProfilesPage,
});

async function listProfiles(): Promise<Profile[]> {
  const { data, error } = await supabase
    .from("profiles")
    .select("*")
    .order("profile_index", { ascending: true });
  if (error) throw error;
  return (data ?? []) as Profile[];
}

interface AvatarEntry {
  id: string;
  display_name: string;
  storage_path: string;
  category: string;
  bg_color: string | null;
  image_url: string;
}

async function listAvatarCatalog(): Promise<AvatarEntry[]> {
  const { data, error } = await supabase.rpc("get_avatar_catalog");
  if (error) throw error;
  const base = `${import.meta.env.VITE_SUPABASE_URL.replace(/\/$/, "")}/storage/v1/object/public/avatars`;
  return ((data ?? []) as AvatarEntry[]).map((a) => ({
    id: a.id,
    display_name: a.display_name,
    storage_path: a.storage_path,
    category: a.category,
    bg_color: a.bg_color ?? null,
    image_url: `${base}/${a.storage_path}`,
  }));
}

function ProfilesPage() {
  const { data: profiles = [], isLoading } = useQuery({
    queryKey: ["profiles"],
    queryFn: listProfiles,
  });
  const { data: avatarCatalog = [] } = useQuery({
    queryKey: ["avatar-catalog"],
    queryFn: listAvatarCatalog,
    staleTime: 60 * 60 * 1000,
  });
  const avatarById = new Map(avatarCatalog.map((a) => [a.id, a]));

  return (
    <main className="mx-auto flex min-h-[100dvh] w-full max-w-3xl flex-col p-6">
      <header className="mb-8 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Pick a profile</h1>
        <button
          type="button"
          onClick={async () => {
            await supabase.auth.signOut();
            window.location.assign("/login");
          }}
          className="flex items-center gap-2 rounded-lg border border-slate-700 px-3 py-2 text-sm text-slate-300 hover:border-primary"
        >
          <LogOut className="h-4 w-4" />
          Sign out
        </button>
      </header>

      {isLoading ? (
        <div className="text-slate-400">Loading…</div>
      ) : profiles.length === 0 ? (
        <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
          <p className="mb-2 font-medium">No profiles yet.</p>
          <p className="text-sm text-slate-400">
            Sign in on a TV first to seed your account, or create a profile in the panel.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          {profiles.map((p) => {
            const avatar = p.avatar_id ? avatarById.get(p.avatar_id) : null;
            return (
            <Link
              key={p.id}
              to="/p/$profileId"
              params={{ profileId: String(p.profile_index) }}
              className="group flex flex-col items-center gap-3 rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 transition hover:border-primary"
            >
              <div
                className="flex h-20 w-20 items-center justify-center overflow-hidden rounded-full text-2xl font-semibold text-white"
                style={{ backgroundColor: avatar?.bg_color ?? p.avatar_color_hex }}
              >
                {avatar ? (
                  <img
                    src={avatar.image_url}
                    alt={avatar.display_name}
                    className="h-full w-full object-cover"
                  />
                ) : p.name ? (
                  p.name[0]?.toUpperCase()
                ) : (
                  <User className="h-8 w-8" />
                )}
              </div>
              <div className="text-center">
                <div className="font-medium text-slate-100">
                  {p.name || `Profile ${p.profile_index}`}
                </div>
                {p.pin_hash && (
                  <div className="mt-1 flex items-center justify-center gap-1 text-xs text-amber-400">
                    <Lock className="h-3 w-3" />
                    PIN protected
                  </div>
                )}
              </div>
            </Link>
            );
          })}
        </div>
      )}
    </main>
  );
}
