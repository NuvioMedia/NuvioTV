import Link from "next/link";
import { Lock, User } from "lucide-react";
import { listProfiles } from "@/lib/data/profiles";
import SignOutButton from "@/components/SignOutButton";

export const metadata = {
  title: "Profiles — NuvioTV Panel",
};

export default async function ProfilesPage() {
  const profiles = await listProfiles();

  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col p-6">
      <header className="mb-8 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Pick a profile</h1>
        <SignOutButton />
      </header>

      {profiles.length === 0 ? (
        <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
          <p>No profiles yet. Sign in on a TV first to seed your account.</p>
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          {profiles.map((p) => (
            <Link
              key={p.id}
              href={`/p/${p.profile_index}`}
              className="group flex flex-col items-center gap-3 rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 transition hover:border-primary"
            >
              <div
                className="flex h-20 w-20 items-center justify-center rounded-full text-2xl font-semibold text-white"
                style={{ backgroundColor: p.avatar_color_hex }}
              >
                {p.name ? p.name[0]?.toUpperCase() : <User className="h-8 w-8" />}
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
          ))}
        </div>
      )}
    </main>
  );
}
