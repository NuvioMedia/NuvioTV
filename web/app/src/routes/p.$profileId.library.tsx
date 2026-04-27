import { createFileRoute, Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Bookmark, Clock } from "lucide-react";
import type { LibraryItem, WatchProgressRow } from "@omnio/shared/supabase";
import { supabase } from "@/lib/supabase";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/library")({
  component: LibraryPage,
});

function LibraryPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);

  const { data: progress } = useQuery({
    queryKey: ["watch_progress", profileId],
    queryFn: async (): Promise<WatchProgressRow[]> => {
      const { data, error } = await supabase
        .from("watch_progress")
        .select("*")
        .eq("profile_id", profileId)
        .order("last_watched", { ascending: false })
        .limit(30);
      if (error) throw error;
      return (data ?? []) as WatchProgressRow[];
    },
  });

  const { data: library } = useQuery({
    queryKey: ["library_items", profileId],
    queryFn: async (): Promise<LibraryItem[]> => {
      const { data, error } = await supabase
        .from("library_items")
        .select("*")
        .eq("profile_id", profileId)
        .order("added_at", { ascending: false })
        .limit(60);
      if (error) throw error;
      return (data ?? []) as LibraryItem[];
    },
  });

  const inProgress = (progress ?? []).filter(
    (p) => p.duration > 0 && p.position / p.duration < 0.95
  );

  return (
    <div className="mx-auto w-full max-w-7xl space-y-10 p-6">
      <h1 className="text-2xl font-semibold">Library</h1>

      <section>
        <h2 className="mb-3 flex items-center gap-2 text-lg font-medium text-slate-200">
          <Clock className="h-4 w-4" />
          Continue watching
        </h2>
        {inProgress.length === 0 ? (
          <div className="rounded-lg border border-slate-800 bg-slate-900/40 p-4 text-sm text-slate-500">
            Nothing in progress.
          </div>
        ) : (
          <ul className="grid gap-2 sm:grid-cols-2">
            {inProgress.map((p) => (
              <li key={p.id}>
                <Link
                  to="/p/$profileId/detail/$type/$id"
                  params={{
                    profileId: String(profileId),
                    type: p.content_type,
                    id: p.content_id,
                  }}
                  className="block rounded-lg border border-slate-800 bg-slate-900/40 p-3 hover:border-primary"
                >
                  <div className="text-sm text-slate-200">
                    {p.content_id}
                    {p.season != null && p.episode != null
                      ? ` — S${p.season}E${p.episode}`
                      : ""}
                  </div>
                  <ProgressBar
                    position={p.position}
                    duration={p.duration}
                    className="mt-2"
                  />
                  <div className="mt-1 flex items-center justify-between text-xs text-slate-500">
                    <span>{formatDuration(p.position)} / {formatDuration(p.duration)}</span>
                    <span>{new Date(p.last_watched).toLocaleDateString()}</span>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2 className="mb-3 flex items-center gap-2 text-lg font-medium text-slate-200">
          <Bookmark className="h-4 w-4" />
          Saved
        </h2>
        {!library || library.length === 0 ? (
          <div className="rounded-lg border border-slate-800 bg-slate-900/40 p-4 text-sm text-slate-500">
            Nothing saved yet.
          </div>
        ) : (
          <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6">
            {library.map((item) => (
              <Link
                key={item.id}
                to="/p/$profileId/detail/$type/$id"
                params={{
                  profileId: String(profileId),
                  type: item.content_type,
                  id: item.content_id,
                }}
                className="group block"
              >
                <div className="aspect-[2/3] overflow-hidden rounded-lg border border-slate-800 bg-slate-900 transition group-hover:border-primary">
                  {item.poster ? (
                    <img
                      src={item.poster}
                      alt={item.name}
                      loading="lazy"
                      className="h-full w-full object-cover"
                    />
                  ) : (
                    <div className="flex h-full items-center justify-center px-2 text-center text-xs text-slate-500">
                      {item.name}
                    </div>
                  )}
                </div>
                <div className="mt-2 line-clamp-2 text-xs text-slate-300">{item.name}</div>
              </Link>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function ProgressBar({
  position,
  duration,
  className,
}: {
  position: number;
  duration: number;
  className?: string;
}) {
  const ratio = duration > 0 ? Math.max(0, Math.min(1, position / duration)) : 0;
  return (
    <div className={`h-1 overflow-hidden rounded-full bg-slate-800 ${className ?? ""}`}>
      <div className="h-full bg-primary" style={{ width: `${ratio * 100}%` }} />
    </div>
  );
}

function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return "0:00";
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}
