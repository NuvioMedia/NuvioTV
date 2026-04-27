import { Link, Outlet, createFileRoute, redirect } from "@tanstack/react-router";
import { Home, Search, Library, Settings } from "lucide-react";
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

  return (
    <div className="flex min-h-full flex-col">
      <header className="sticky top-0 z-10 flex items-center gap-6 border-b border-slate-800/60 bg-slate-950/80 px-6 py-3 backdrop-blur">
        <div className="text-lg font-semibold">OmnioTV</div>
        <nav className="flex items-center gap-1 text-sm">
          <NavLink to="/p/$profileId" params={{ profileId: String(profileId) }} icon={<Home className="h-4 w-4" />}>
            Home
          </NavLink>
          <NavLink
            to="/p/$profileId/search"
            params={{ profileId: String(profileId) }}
            icon={<Search className="h-4 w-4" />}
          >
            Search
          </NavLink>
          <NavLink
            to="/p/$profileId/library"
            params={{ profileId: String(profileId) }}
            icon={<Library className="h-4 w-4" />}
          >
            Library
          </NavLink>
        </nav>
        <div className="ml-auto flex items-center gap-3 text-sm text-slate-400">
          <Link to="/profiles" className="hover:text-slate-200">
            Switch profile
          </Link>
          <span className="hidden h-4 w-px bg-slate-700 sm:block" />
          <button
            type="button"
            onClick={() => alert("Settings coming in Phase 3")}
            className="flex items-center gap-1 text-slate-400 hover:text-slate-200"
          >
            <Settings className="h-4 w-4" />
            Settings
          </button>
        </div>
      </header>
      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
}

function NavLink({
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
