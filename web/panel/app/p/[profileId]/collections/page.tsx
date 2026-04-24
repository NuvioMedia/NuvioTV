import { Pin } from "lucide-react";
import { listCollections } from "@/lib/data/collections";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function CollectionsPage({ params }: Props) {
  const { profileId } = await params;
  const collections = await listCollections(Number.parseInt(profileId, 10));

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Collections</h1>
        <p className="text-sm text-slate-400">
          Custom row collections you&apos;ve assembled on the TV.
        </p>
      </header>

      {collections.length === 0 ? (
        <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
          No collections yet.
        </div>
      ) : (
        <div className="space-y-4">
          {collections.map((c) => {
            const folders = c.folders ?? [];
            return (
              <div
                key={c.id}
                className="overflow-hidden rounded-2xl border border-slate-700/50 bg-slate-800/40"
              >
                {c.backdropImageUrl && (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={c.backdropImageUrl}
                    alt=""
                    className="h-32 w-full object-cover opacity-70"
                  />
                )}
                <div className="p-4">
                  <div className="mb-1 flex items-center gap-2">
                    <h2 className="text-lg font-semibold">{c.title}</h2>
                    {c.pinToTop && (
                      <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/20 px-2 py-0.5 text-xs text-amber-300">
                        <Pin className="h-3 w-3" /> pinned
                      </span>
                    )}
                  </div>
                  <div className="mb-3 text-xs text-slate-500">
                    {folders.length} folder{folders.length === 1 ? "" : "s"}
                    {c.viewMode ? ` • ${c.viewMode}` : ""}
                  </div>
                  {folders.length > 0 && (
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
                      {folders.map((f) => (
                        <div
                          key={f.id}
                          className="rounded-lg border border-slate-700/40 bg-slate-900/40 p-3"
                        >
                          <div className="flex items-center gap-2">
                            {f.coverEmoji && <span className="text-lg">{f.coverEmoji}</span>}
                            <span className="truncate text-sm font-medium">{f.title}</span>
                          </div>
                          <div className="mt-1 text-xs text-slate-500">
                            {(f.catalogSources ?? []).length} source
                            {(f.catalogSources ?? []).length === 1 ? "" : "s"}
                            {f.tileShape ? ` • ${f.tileShape.toLowerCase()}` : ""}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
