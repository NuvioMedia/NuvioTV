import { useState } from "react";
import { createFileRoute } from "@tanstack/react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle, Unlink, Loader2 } from "lucide-react";
import { authenticateEmby, clearEmbyCreds, loadEmbyCreds, saveEmbyCreds } from "@/lib/emby";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/settings/emby")({
  component: EmbySettingsPage,
});

function EmbySettingsPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);
  const queryClient = useQueryClient();

  const { data: creds, isLoading } = useQuery({
    queryKey: ["emby", profileId],
    queryFn: () => loadEmbyCreds(profileId),
  });

  const [serverUrl, setServerUrl] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleConnect(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const auth = await authenticateEmby(serverUrl.trim(), username.trim(), password);
      await saveEmbyCreds(profileId, auth);
      setUsername("");
      setPassword("");
      await queryClient.invalidateQueries({ queryKey: ["emby", profileId] });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDisconnect() {
    await clearEmbyCreds(profileId);
    await queryClient.invalidateQueries({ queryKey: ["emby", profileId] });
  }

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold">Emby</h2>
      <p className="text-sm text-slate-400">
        Connect a self-hosted Emby server to sync playback state and keep watched items in lockstep
        across the OmnioTV TV app and the web. Credentials are stored encrypted in Supabase and
        shared with your TV profile.
      </p>

      {isLoading ? (
        <div className="flex items-center gap-2 text-slate-400">
          <Loader2 className="h-4 w-4 animate-spin" />
          Loading…
        </div>
      ) : creds ? (
        <div className="space-y-3 rounded-lg border border-emerald-500/40 bg-emerald-500/10 p-4">
          <div className="flex items-center gap-2 text-emerald-300">
            <CheckCircle className="h-5 w-5" />
            <span className="font-medium">Connected</span>
          </div>
          <div className="space-y-1 text-xs text-slate-400">
            <div>
              Server: <code className="rounded bg-slate-800 px-1">{creds.serverUrl}</code>
            </div>
            <div>
              User ID: <code className="rounded bg-slate-800 px-1">{creds.userId}</code>
            </div>
          </div>
          <button
            type="button"
            onClick={handleDisconnect}
            className="flex items-center gap-2 rounded-lg border border-slate-700 px-3 py-2 text-sm text-slate-200 hover:border-red-500/60 hover:text-red-200"
          >
            <Unlink className="h-4 w-4" /> Disconnect
          </button>
        </div>
      ) : (
        <form onSubmit={handleConnect} className="space-y-3 rounded-lg border border-slate-800 bg-slate-900/40 p-4">
          <Field label="Server URL" required>
            <input
              type="url"
              required
              placeholder="https://emby.example.com"
              value={serverUrl}
              onChange={(e) => setServerUrl(e.target.value)}
              className="w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm outline-none focus:border-primary"
            />
          </Field>
          <Field label="Username" required>
            <input
              type="text"
              required
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm outline-none focus:border-primary"
            />
          </Field>
          <Field label="Password" required>
            <input
              type="password"
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm outline-none focus:border-primary"
            />
          </Field>

          {error && (
            <div className="rounded-md border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-200">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white hover:bg-primary-hover disabled:opacity-50"
          >
            {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
            Connect
          </button>
        </form>
      )}
    </div>
  );
}

function Field({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs uppercase tracking-wide text-slate-500">
        {label}
        {required && <span className="ml-1 text-red-400">*</span>}
      </span>
      {children}
    </label>
  );
}
