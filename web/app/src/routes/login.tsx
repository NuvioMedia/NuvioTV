import { useState } from "react";
import { Loader2 } from "lucide-react";
import { createFileRoute, redirect, useNavigate } from "@tanstack/react-router";
import { supabase } from "@/lib/supabase";

export const Route = createFileRoute("/login")({
  beforeLoad: async () => {
    const {
      data: { session },
    } = await supabase.auth.getSession();
    if (session) throw redirect({ to: "/profiles" });
  },
  component: LoginPage,
});

// Sign-up is intentionally disabled here. New accounts are created via the
// Android TV app (or by an admin in the Supabase dashboard). Flip this to
// `true` to re-expose the sign-up toggle when registration reopens. See the
// "Closed registration" section in web/app/README.md.
const SIGNUP_ENABLED = false;

function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [isPending, setIsPending] = useState(false);

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setInfo(null);
    setIsPending(true);

    try {
      if (mode === "signin") {
        const { error: signInError } = await supabase.auth.signInWithPassword({
          email: email.trim(),
          password,
        });
        if (signInError) {
          setError(friendly(signInError.message));
          return;
        }
        navigate({ to: "/profiles" });
        return;
      }

      const { data, error: signUpError } = await supabase.auth.signUp({
        email: email.trim(),
        password,
      });
      if (signUpError) {
        setError(friendly(signUpError.message));
        return;
      }
      if (!data.session) {
        setInfo("Check your email to confirm your account, then sign in.");
        setMode("signin");
        return;
      }
      navigate({ to: "/profiles" });
    } finally {
      setIsPending(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-[100dvh] w-full max-w-md flex-col justify-center p-6">
      <h1 className="mb-6 text-3xl font-semibold tracking-tight">OmnioTV</h1>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="mb-1 block text-sm text-slate-300" htmlFor="email">
            Email
          </label>
          <input
            id="email"
            type="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            className="w-full rounded-lg border border-slate-700 bg-slate-900/60 px-3 py-2 text-slate-50 outline-none focus:border-primary"
          />
        </div>
        <div>
          <label className="mb-1 block text-sm text-slate-300" htmlFor="password">
            Password
          </label>
          <input
            id="password"
            type="password"
            required
            minLength={6}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === "signin" ? "current-password" : "new-password"}
            className="w-full rounded-lg border border-slate-700 bg-slate-900/60 px-3 py-2 text-slate-50 outline-none focus:border-primary"
          />
        </div>

        {error && (
          <div className="rounded-lg border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-200">
            {error}
          </div>
        )}
        {info && (
          <div className="rounded-lg border border-blue-500/40 bg-blue-500/10 px-3 py-2 text-sm text-blue-200">
            {info}
          </div>
        )}

        <button
          type="submit"
          disabled={isPending}
          className="flex w-full items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 font-medium text-white transition hover:bg-primary-hover disabled:opacity-50"
        >
          {isPending && <Loader2 className="h-4 w-4 animate-spin" />}
          {mode === "signin" ? "Sign in" : "Create account"}
        </button>

        {SIGNUP_ENABLED ? (
          <button
            type="button"
            onClick={() => {
              setMode((m) => (m === "signin" ? "signup" : "signin"));
              setError(null);
              setInfo(null);
            }}
            className="block w-full text-center text-sm text-slate-400 hover:text-slate-200"
          >
            {mode === "signin" ? "Need an account? Sign up" : "Have an account? Sign in"}
          </button>
        ) : (
          <p className="text-center text-xs text-slate-500">
            Registrations are closed. Sign up via the Android TV app or contact the admin.
          </p>
        )}
      </form>
    </main>
  );
}

function friendly(message: string): string {
  const m = message.toLowerCase();
  if (m.includes("invalid login credentials")) return "Incorrect email or password.";
  if (m.includes("email not confirmed")) return "Please confirm your email first.";
  if (m.includes("user already registered"))
    return "An account with this email already exists. Try signing in instead.";
  if (m.includes("invalid email")) return "Please enter a valid email address.";
  if (m.includes("password") && m.includes("short")) return "Password is too short.";
  if (m.includes("rate limit")) return "Too many attempts. Please try again later.";
  if (m.includes("signups not allowed") || m.includes("signup is disabled")) {
    return "Registrations are closed.";
  }
  return message;
}
