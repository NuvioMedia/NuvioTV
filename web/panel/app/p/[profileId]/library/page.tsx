import { listLibrary, listRecentlyWatched } from "@/lib/data/library";

interface Props {
  params: Promise<{ profileId: string }>;
}

function formatDate(epochMs: number): string {
  if (!epochMs) return "—";
  const d = new Date(epochMs);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleDateString();
}

function formatEpisodeLabel(season: number | null, episode: number | null): string {
  if (season == null && episode == null) return "";
  const s = season != null ? `S${String(season).padStart(2, "0")}` : "";
  const e = episode != null ? `E${String(episode).padStart(2, "0")}` : "";
  return [s, e].filter(Boolean).join("");
}

export default async function LibraryPage({ params }: Props) {
  const { profileId } = await params;
  const idx = Number.parseInt(profileId, 10);
  const [library, watched] = await Promise.all([listLibrary(idx), listRecentlyWatched(idx, 30)]);

  return (
    <div className="space-y-8">
      <header>
        <h1 className="text-2xl font-semibold">Library &amp; Watched</h1>
        <p className="text-sm text-slate-400">
          Saved titles and recently-watched episodes for this profile.
        </p>
      </header>

      <section>
        <h2 className="mb-3 text-lg font-medium">Library ({library.length})</h2>
        {library.length === 0 ? (
          <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
            Empty.
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
            {library.map((item) => (
              <div
                key={item.id}
                className="overflow-hidden rounded-xl border border-slate-700/50 bg-slate-800/40"
              >
                {item.poster ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={item.poster}
                    alt=""
                    className="aspect-[2/3] w-full bg-slate-900 object-cover"
                  />
                ) : (
                  <div className="flex aspect-[2/3] w-full items-center justify-center bg-slate-900 text-xs text-slate-600">
                    no poster
                  </div>
                )}
                <div className="p-2">
                  <div className="truncate text-sm font-medium">{item.name || "—"}</div>
                  <div className="text-xs text-slate-500">
                    {item.content_type}
                    {item.release_info ? ` • ${item.release_info}` : ""}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-lg font-medium">Recently watched</h2>
        {watched.length === 0 ? (
          <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
            Nothing here yet.
          </div>
        ) : (
          <div className="overflow-hidden rounded-2xl border border-slate-700/50 bg-slate-800/40">
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-900/40 text-xs uppercase tracking-wide text-slate-400">
                <tr>
                  <th className="px-4 py-3">Title</th>
                  <th className="px-4 py-3">Episode</th>
                  <th className="px-4 py-3">Watched</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700/40">
                {watched.map((w) => (
                  <tr key={w.id}>
                    <td className="px-4 py-3 font-medium">{w.title || "—"}</td>
                    <td className="px-4 py-3 text-slate-400">
                      {formatEpisodeLabel(w.season, w.episode) || "—"}
                    </td>
                    <td className="px-4 py-3 text-slate-400">{formatDate(w.watched_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
