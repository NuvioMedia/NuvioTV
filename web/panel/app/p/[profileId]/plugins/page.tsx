import { ExternalLink } from "lucide-react";
import { listPlugins } from "@/lib/data/addons";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function PluginsPage({ params }: Props) {
  const { profileId } = await params;
  const plugins = await listPlugins(Number.parseInt(profileId, 10));

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Plugins</h1>
        <p className="text-sm text-slate-400">
          Scraper plugins for this profile. The JS code itself stays on the device — v1 shows
          metadata only.
        </p>
      </header>

      {plugins.length === 0 ? (
        <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
          No plugins installed.
        </div>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-slate-700/50 bg-slate-800/40">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-900/40 text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="px-4 py-3">Order</th>
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">URL</th>
                <th className="px-4 py-3">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-700/40">
              {plugins.map((p, i) => (
                <tr key={p.id} className="hover:bg-slate-900/30">
                  <td className="px-4 py-3 text-slate-400">{i + 1}</td>
                  <td className="px-4 py-3 font-medium">{p.name || "—"}</td>
                  <td className="px-4 py-3 text-slate-300">
                    <a
                      href={p.url}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 hover:text-primary"
                    >
                      <span className="break-all">{p.url}</span>
                      <ExternalLink className="h-3 w-3 shrink-0" />
                    </a>
                  </td>
                  <td className="px-4 py-3">
                    {p.enabled ? (
                      <span className="rounded-full bg-emerald-500/20 px-2 py-0.5 text-xs text-emerald-300">
                        enabled
                      </span>
                    ) : (
                      <span className="rounded-full bg-slate-700/50 px-2 py-0.5 text-xs text-slate-400">
                        disabled
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
