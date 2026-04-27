import { createFileRoute, Link, Outlet } from "@tanstack/react-router";
import { Tv, KeyRound, Puzzle, Bookmark } from "lucide-react";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/settings")({
  component: SettingsLayout,
});

function SettingsLayout() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);

  return (
    <div className="mx-auto w-full max-w-5xl gap-6 p-6 md:flex">
      <aside className="md:w-56 md:shrink-0">
        <h1 className="mb-4 text-2xl font-semibold">Settings</h1>
        <nav className="flex flex-row flex-wrap gap-1 md:flex-col md:gap-0">
          <SettingsLink
            to="/p/$profileId/settings/trakt"
            params={{ profileId: String(profileId) }}
            icon={<KeyRound className="h-4 w-4" />}
          >
            Trakt
          </SettingsLink>
          <SettingsLink
            to="/p/$profileId/settings/emby"
            params={{ profileId: String(profileId) }}
            icon={<Tv className="h-4 w-4" />}
          >
            Emby
          </SettingsLink>
          <SettingsLink
            to="/p/$profileId/settings/plugins"
            params={{ profileId: String(profileId) }}
            icon={<Puzzle className="h-4 w-4" />}
          >
            Plugins
          </SettingsLink>
          <SettingsLink
            to="/p/$profileId/settings/collections"
            params={{ profileId: String(profileId) }}
            icon={<Bookmark className="h-4 w-4" />}
          >
            Collections
          </SettingsLink>
        </nav>
      </aside>
      <section className="mt-6 flex-1 md:mt-0">
        <Outlet />
      </section>
    </div>
  );
}

function SettingsLink({
  to,
  params,
  icon,
  children,
}: {
  to: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  params: any;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <Link
      to={to}
      params={params}
      activeProps={{ className: "bg-slate-800 text-slate-100" }}
      className="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-slate-300 hover:bg-slate-800/60 hover:text-slate-100"
    >
      {icon}
      {children}
    </Link>
  );
}
