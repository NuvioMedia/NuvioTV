import { useEffect, useState } from "react";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { Loader2, AlertTriangle, CheckCircle } from "lucide-react";
import {
  consumeStoredState,
  exchangeTraktCode,
  saveTraktTokens,
} from "@/lib/trakt";

interface CallbackSearch {
  code?: string;
  state?: string;
  error?: string;
}

export const Route = createFileRoute("/auth/trakt/callback")({
  validateSearch: (s: Record<string, unknown>): CallbackSearch => ({
    code: typeof s.code === "string" ? s.code : undefined,
    state: typeof s.state === "string" ? s.state : undefined,
    error: typeof s.error === "string" ? s.error : undefined,
  }),
  component: TraktCallback,
});

type Status = "exchanging" | "ok" | "err";

function TraktCallback() {
  const search = Route.useSearch();
  const navigate = useNavigate();
  const [status, setStatus] = useState<Status>("exchanging");
  const [message, setMessage] = useState<string | null>(null);
  const [profileId, setProfileId] = useState<number | null>(null);

  useEffect(() => {
    if (search.error) {
      setStatus("err");
      setMessage(`Trakt rejected the request: ${search.error}`);
      return;
    }
    if (!search.code || !search.state) {
      setStatus("err");
      setMessage("Missing code/state from Trakt callback.");
      return;
    }
    const [profileIdStr] = search.state.split(":");
    const parsedProfileId = Number(profileIdStr);
    if (!Number.isFinite(parsedProfileId)) {
      setStatus("err");
      setMessage("Invalid state — possible CSRF.");
      return;
    }
    setProfileId(parsedProfileId);
    const expected = consumeStoredState(parsedProfileId);
    if (expected !== search.state) {
      setStatus("err");
      setMessage("State mismatch — possible CSRF, please retry.");
      return;
    }

    const redirectUri = `${window.location.origin}/auth/trakt/callback`;
    exchangeTraktCode({ code: search.code, redirect_uri: redirectUri })
      .then((raw) => {
        saveTraktTokens(parsedProfileId, raw);
        setStatus("ok");
        setMessage("Trakt connected.");
        // Bounce back to the settings page after a beat.
        window.setTimeout(() => {
          navigate({
            to: "/p/$profileId/settings/trakt",
            params: { profileId: String(parsedProfileId) },
          });
        }, 800);
      })
      .catch((err: Error) => {
        setStatus("err");
        setMessage(err.message);
      });
  }, [search.code, search.state, search.error, navigate]);

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      {status === "exchanging" && (
        <>
          <Loader2 className="h-8 w-8 animate-spin text-primary" />
          <p className="text-sm text-slate-300">Connecting your Trakt account…</p>
        </>
      )}
      {status === "ok" && (
        <>
          <CheckCircle className="h-8 w-8 text-emerald-400" />
          <p className="text-sm text-slate-200">{message}</p>
        </>
      )}
      {status === "err" && (
        <>
          <AlertTriangle className="h-8 w-8 text-amber-400" />
          <p className="text-sm text-amber-200">{message}</p>
          {profileId != null && (
            <button
              type="button"
              onClick={() =>
                navigate({
                  to: "/p/$profileId/settings/trakt",
                  params: { profileId: String(profileId) },
                })
              }
              className="rounded-lg border border-slate-700 px-3 py-1.5 text-sm text-slate-200 hover:border-primary"
            >
              Back to settings
            </button>
          )}
        </>
      )}
    </main>
  );
}
