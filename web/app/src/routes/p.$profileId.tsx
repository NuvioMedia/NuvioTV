import { Link, Outlet, createFileRoute, redirect } from "@tanstack/react-router";
import { Home, Search, Library, Settings, UsersRound } from "lucide-react";
import { supabase } from "@/lib/supabase";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId")({
  beforeLoad: async () => {
    const {
      data: { session },
    } = await supabase.auth.getSession();
    if (!session) throw redirect({ to: "/login" });
  },
  component: ProfileLayout,
});

function ProfileLayout() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);
  const profileParams = { profileId: String(profileId) };

  return (
    <div className="flex min-h-full flex-col">
      {/* Desktop / tablet — top header */}
      <header
        className="sticky top-0 z-10 hidden items-center gap-6 border-b border-slate-800/60 bg-slate-950/80 px-6 py-3 backdrop-blur sm:flex"
        style={{ paddingTop: "max(env(safe-area-inset-top, 0px), 12px)" }}
      >
        <div className="text-lg font-semibold">OmnioTV</div>
        <nav className="flex items-center gap-1 text-sm">
          <DesktopNav to="/p/$profileId" params={profileParams} icon={<Home className="h-4 w-4" />}>
            Home
          </DesktopNav>
          <DesktopNav
            to="/p/$profileId/search"
            params={profileParams}
            icon={<Search className="h-4 w-4" />}
          >
            Search
          </DesktopNav>
          <DesktopNav
            to="/p/$profileId/library"
            params={profileParams}
            icon={<Library className="h-4 w-4" />}
          >
            Library
          </DesktopNav>
        </nav>
        <div className="ml-auto flex items-center gap-3 text-sm text-slate-400">
          <Link to="/profiles" className="hover:text-slate-200">
            Switch profile
          </Link>
          <span className="hidden h-4 w-px bg-slate-700 sm:block" />
          <Link
            to="/p/$profileId/settings/trakt"
            params={profileParams}
            className="flex items-center gap-1 text-slate-400 hover:text-slate-200"
          >
            <Settings className="h-4 w-4" />
            Settings
          </Link>
        </div>
      </header>

      {/* Mobile — minimal top bar with brand + switch profile */}
      <header
        className="sticky top-0 z-10 flex items-center justify-between border-b border-slate-800/60 bg-slate-950/80 px-4 py-3 backdrop-blur sm:hidden"
        style={{ paddingTop: "max(env(safe-area-inset-top, 0px), 12px)" }}
      >
        <div className="text-base font-semibold">OmnioTV</div>
        <Link
          to="/profiles"
          className="flex h-9 items-center gap-1 rounded-md px-2 text-sm text-slate-400 hover:text-slate-200"
        >
          <UsersRound className="h-4 w-4" />
        </Link>
      </header>

      {/* Pad the bottom of the main area on mobile so the tab bar doesn't overlap content */}
      <main
        className="flex-1 sm:pb-0"
        style={{ paddingBottom: "calc(env(safe-area-inset-bottom, 0px) + 64px)" }}
      >
        <Outlet />
      </main>

      {/* Mobile bottom tab bar — hidden ≥sm */}
      <nav
        className="fixed inset-x-0 bottom-0 z-20 flex border-t border-slate-800 bg-slate-950/95 backdrop-blur sm:hidden"
        style={{ paddingBottom: "env(safe-area-inset-bottom, 0px)" }}
      >
        <BottomTab to="/p/$profileId" params={profileParams} icon={<Home className="h-5 w-5" />}>
          Home
        </BottomTab>
        <BottomTab
          to="/p/$profileId/search"
          params={profileParams}
          icon={<Search className="h-5 w-5" />}
        >
          Search
        </BottomTab>
        <BottomTab
          to="/p/$profileId/library"
          params={profileParams}
          icon={<Library className="h-5 w-5" />}
        >
          Library
        </BottomTab>
        <BottomTab
          to="/p/$profileId/settings/trakt"
          params={profileParams}
          icon={<Settings className="h-5 w-5" />}
        >
          Settings
        </BottomTab>
      </nav>
    </div>
  );
}

function DesktopNav({
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
      className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-slate-300 hover:bg-slate-800/60 hover:text-slate-100"
    >
      {icon}
      {children}
    </Link>
  );
}

function BottomTab({
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
      activeProps={{ className: "text-primary" }}
      className="flex flex-1 flex-col items-center justify-center gap-0.5 py-2 text-[11px] text-slate-400 hover:text-slate-200"
    >
      {icon}
      {children}
    </Link>
  );
}
