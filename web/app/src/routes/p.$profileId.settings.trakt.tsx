import { useState } from "react";
import { createFileRoute } from "@tanstack/react-router";
import { ExternalLink, Unlink, CheckCircle } from "lucide-react";
import {
  clearTraktTokens,
  loadTraktTokens,
  startTraktAuth,
} from "@/lib/trakt";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/settings/trakt")({
  component: TraktSettingsPage,
});

function TraktSettingsPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);
  const [tokens, setTokens] = useState(() => loadTraktTokens(profileId));
  const [error, setError] = useState<string | null>(null);

  const clientIdConfigured = !!import.meta.env.VITE_TRAKT_CLIENT_ID;
  const isConnected = tokens && tokens.expires_at > Date.now();

  function handleConnect() {
    setError(null);
    try {
      startTraktAuth(profileId);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function handleDisconnect() {
    clearTraktTokens(profileId);
    setTokens(null);
  }

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold">Trakt</h2>
      <p className="text-sm text-slate-400">
        Connect Trakt to scrobble your watch progress, sync history, and unlock cross-device
        watchlists. Tokens are stored on this device only — switching browsers or clearing site data
        will require reconnecting.
      </p>

      {!clientIdConfigured && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm text-amber-200">
          <strong>Trakt isn't configured for this deployment.</strong> Set{" "}
          <code className="rounded bg-amber-900/40 px-1">VITE_TRAKT_CLIENT_ID</code> in the web
          environment and{" "}
          <code className="rounded bg-amber-900/40 px-1">TRAKT_CLIENT_ID</code> +{" "}
          <code className="rounded bg-amber-900/40 px-1">TRAKT_CLIENT_SECRET</code> on the server,
          then redeploy.
        </div>
      )}

      {error && (
        <div className="rounded-lg border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-200">
          {error}
        </div>
      )}

      {isConnected ? (
        <div className="space-y-3 rounded-lg border border-emerald-500/40 bg-emerald-500/10 p-4">
          <div className="flex items-center gap-2 text-emerald-300">
            <CheckCircle className="h-5 w-5" />
            <span className="font-medium">Connected</span>
          </div>
          <p className="text-xs text-slate-400">
            Token expires {new Date(tokens.expires_at).toLocaleString()}. Auto-refreshes shortly
            before that.
          </p>
          <button
            type="button"
            onClick={handleDisconnect}
            className="flex items-center gap-2 rounded-lg border border-slate-700 px-3 py-2 text-sm text-slate-200 hover:border-red-500/60 hover:text-red-200"
          >
            <Unlink className="h-4 w-4" /> Disconnect
          </button>
        </div>
      ) : (
        <button
          type="button"
          onClick={handleConnect}
          disabled={!clientIdConfigured}
          className="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 font-medium text-white hover:bg-primary-hover disabled:opacity-50"
        >
          <ExternalLink className="h-4 w-4" />
          Connect Trakt
        </button>
      )}
    </div>
  );
}
