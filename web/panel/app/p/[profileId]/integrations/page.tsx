import { CheckCircle2, MinusCircle, AlertTriangle } from "lucide-react";
import { getSettingsSnapshot } from "@/lib/data/settings";

interface Props {
  params: Promise<{ profileId: string }>;
}

interface IntegrationRow {
  name: string;
  blurb: string;
  state: "connected" | "disabled" | "missing";
  detail?: string;
  warning?: string;
}

function rowFromBoolean(
  enabled: boolean | undefined,
  detail: string | undefined
): Pick<IntegrationRow, "state" | "detail"> {
  if (enabled === undefined) return { state: "missing" };
  if (!enabled) return { state: "disabled", detail };
  return { state: "connected", detail };
}

export default async function IntegrationsPage({ params }: Props) {
  const { profileId } = await params;
  const snap = await getSettingsSnapshot(Number.parseInt(profileId, 10));

  const tmdb = snap.features.tmdb_settings ?? {};
  const mdblist = snap.features.mdblist_settings ?? {};
  const animeskip = snap.features.animeskip_settings ?? {};
  const trakt = snap.features.trakt_settings ?? {};
  const emby = snap.features.emby_credentials ?? {};

  const rows: IntegrationRow[] = [
    {
      name: "TMDB",
      blurb: "Artwork, episode metadata, more like this.",
      ...rowFromBoolean(
        tmdb.tmdb_enabled as boolean | undefined,
        typeof tmdb.tmdb_language === "string" ? `language: ${tmdb.tmdb_language}` : undefined
      ),
    },
    {
      name: "Trakt",
      blurb: "Watch progress sync. OAuth lives on the TV.",
      state: trakt.watch_progress_source === "TRAKT" ? "connected" : "disabled",
      detail:
        typeof trakt.continue_watching_days_cap === "number"
          ? `${trakt.continue_watching_days_cap}d cap`
          : undefined,
      warning:
        "OAuth tokens stay on the TV. Re-auth from Settings → Integrations on a TV device.",
    },
    {
      name: "MDBList",
      blurb: "Aggregate ratings (IMDb / Trakt / Letterboxd / etc.).",
      ...rowFromBoolean(
        mdblist.mdblist_enabled as boolean | undefined,
        typeof mdblist.mdblist_api_key === "string" && (mdblist.mdblist_api_key as string).length > 0
          ? "api key set"
          : "no api key"
      ),
    },
    {
      name: "AnimeSkip",
      blurb: "Anime intro/outro skip timestamps.",
      ...rowFromBoolean(
        animeskip.animeskip_enabled as boolean | undefined,
        typeof animeskip.animeskip_client_id === "string" &&
          (animeskip.animeskip_client_id as string).length > 0
          ? "client id set"
          : "no client id"
      ),
    },
    {
      name: "Emby",
      blurb: "Personal Emby server library.",
      state:
        typeof emby.emby_server_url === "string" &&
        (emby.emby_server_url as string).length > 0 &&
        typeof emby.emby_api_key === "string" &&
        (emby.emby_api_key as string).length > 0
          ? "connected"
          : "missing",
      detail:
        typeof emby.emby_server_url === "string"
          ? (emby.emby_server_url as string)
          : undefined,
    },
  ];

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Integrations</h1>
        <p className="text-sm text-slate-400">Status of each connected service.</p>
      </header>

      {snap.updatedAt === null && (
        <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
          No settings have been synced from a TV for this profile yet.
        </div>
      )}

      {Object.keys(snap.validationErrors).length > 0 && (
        <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
          <strong className="font-semibold">Schema drift detected.</strong> Some features
          contain keys the panel does not yet model:
          <ul className="ml-4 mt-2 list-disc">
            {Object.entries(snap.validationErrors).map(([k, msg]) => (
              <li key={k}>
                <code className="font-mono">{k}</code>: {msg}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="grid gap-3 md:grid-cols-2">
        {rows.map((row) => (
          <div
            key={row.name}
            className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-4"
          >
            <div className="mb-2 flex items-center justify-between">
              <h2 className="font-medium text-slate-100">{row.name}</h2>
              {row.state === "connected" && (
                <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/20 px-2 py-0.5 text-xs text-emerald-300">
                  <CheckCircle2 className="h-3 w-3" /> connected
                </span>
              )}
              {row.state === "disabled" && (
                <span className="inline-flex items-center gap-1 rounded-full bg-slate-700/50 px-2 py-0.5 text-xs text-slate-400">
                  <MinusCircle className="h-3 w-3" /> disabled
                </span>
              )}
              {row.state === "missing" && (
                <span className="inline-flex items-center gap-1 rounded-full bg-slate-700/50 px-2 py-0.5 text-xs text-slate-500">
                  not configured
                </span>
              )}
            </div>
            <p className="text-sm text-slate-400">{row.blurb}</p>
            {row.detail && <p className="mt-2 text-xs text-slate-500">{row.detail}</p>}
            {row.warning && (
              <p className="mt-3 inline-flex items-start gap-1 text-xs text-amber-300">
                <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
                {row.warning}
              </p>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
