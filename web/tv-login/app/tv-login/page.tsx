"use client";

import { useEffect, useState, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import type { Session } from "@supabase/supabase-js";
import { supabase } from "../../lib/supabase";
import { Tv, Loader2, CheckCircle2, XCircle, MailCheck } from "lucide-react";

type AuthMode = "signin" | "signup";
type Status = "checking" | "needs_auth" | "approving" | "success" | "error" | "confirm_email";

function formatErrorMessage(error: unknown, fallback: string) {
  const rawMessage = error instanceof Error ? error.message : fallback;
  const message = rawMessage.toLowerCase();

  if (message.includes("invalid login credentials")) {
    return "Incorrect email or password.";
  }

  if (message.includes("email not confirmed")) {
    return "Please confirm your email first.";
  }

  if (message.includes("user already registered")) {
    return "An account with this email already exists. Try signing in instead.";
  }

  if (message.includes("invalid email")) {
    return "Please enter a valid email address.";
  }

  if (message.includes("password") && message.includes("short")) {
    return "Password is too short.";
  }

  if (message.includes("password") && message.includes("weak")) {
    return "Password is too weak.";
  }

  if (message.includes("signup is disabled")) {
    return "Account creation is currently disabled.";
  }

  if (message.includes("rate limit") || message.includes("too many requests")) {
    return "Too many attempts. Please try again later.";
  }

  if (message.includes("tv login") && message.includes("expired")) {
    return "This TV login code has expired. Scan the QR code on your TV again.";
  }

  if (message.includes("tv login") && message.includes("invalid")) {
    return "This TV login code is invalid. Scan the QR code on your TV again.";
  }

  if (message.includes("approve_tv_login_session") && message.includes("could not find")) {
    return "The TV login service is unavailable right now.";
  }

  return fallback;
}

function getEmailRedirectUrl(code: string | null) {
  if (typeof window === "undefined") {
    return undefined;
  }

  const redirectUrl = new URL("/tv-login", window.location.origin);

  if (code) {
    redirectUrl.searchParams.set("code", code);
  }

  return redirectUrl.toString();
}

function TvLoginContent() {
  const searchParams = useSearchParams();
  const code = searchParams.get("code");

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [authMode, setAuthMode] = useState<AuthMode>("signin");
  const [loading, setLoading] = useState(true);
  const [authLoading, setAuthLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<Status>("checking");
  const [session, setSession] = useState<Session | null>(null);

  useEffect(() => {
    void checkSession();
  }, [code]);

  const approveCode = async () => {
    if (!code) {
      return;
    }

    setLoading(true);

    try {
      const { error } = await supabase.rpc("approve_tv_login_session", {
        p_code: code,
      });

      if (error) {
        throw error;
      }

      setStatus("success");
    } catch (err: unknown) {
      setError(formatErrorMessage(err, "Failed to approve TV login. The code might be expired."));
      setStatus("error");
    } finally {
      setLoading(false);
      setAuthLoading(false);
    }
  };

  const approveAuthenticatedSession = async (nextSession: Session | null) => {
    if (!nextSession) {
      return false;
    }

    setSession(nextSession);
    setStatus("approving");
    await approveCode();
    return true;
  };

  const checkSession = async () => {
    setLoading(true);
    setError(null);

    try {
      const {
        data: { session: currentSession },
      } = await supabase.auth.getSession();

      if (!code) {
        setError("No login code provided. Please scan the QR code on your TV again.");
        setStatus("error");
        return;
      }

      if (await approveAuthenticatedSession(currentSession)) {
        return;
      }

      setStatus("needs_auth");
    } catch (err: unknown) {
      setError(formatErrorMessage(err, "Failed to check session."));
      setStatus("error");
    } finally {
      setLoading(false);
    }
  };

  const handleAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthLoading(true);
    setError(null);

    try {
      if (authMode === "signin") {
        const { data, error } = await supabase.auth.signInWithPassword({
          email,
          password,
        });

        if (error) {
          throw error;
        }

        await approveAuthenticatedSession(data.session);
        return;
      }

      const { data, error } = await supabase.auth.signUp({
        email,
        password,
        options: {
          emailRedirectTo: getEmailRedirectUrl(code),
        },
      });

      if (error) {
        throw error;
      }

      if (await approveAuthenticatedSession(data.session)) {
        return;
      }

      setStatus("confirm_email");
    } catch (err: unknown) {
      setError(formatErrorMessage(err, authMode === "signin" ? "Failed to sign in." : "Failed to create account."));
      setStatus("needs_auth");
    } finally {
      setAuthLoading(false);
    }
  };

  if (loading || status === "checking" || status === "approving") {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center p-4">
        <Loader2 className="h-12 w-12 animate-spin text-primary mb-4" />
        <p className="text-lg font-medium text-slate-300">
          {status === "approving" ? "Approving TV login..." : "Checking session..."}
        </p>
      </div>
    );
  }

  if (status === "success") {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center p-4 text-center">
        <div className="bg-slate-800/50 p-8 rounded-2xl border border-slate-700/50 max-w-md w-full flex flex-col items-center">
          <CheckCircle2 className="h-16 w-16 text-green-500 mb-6" />
          <h1 className="text-2xl font-bold text-white mb-2">TV Login Successful</h1>
          <p className="text-slate-300 mb-6">
            Your Omnio account is now linked to this TV. You can close this window and return to your TV.
          </p>
        </div>
      </div>
    );
  }

  if (status === "confirm_email") {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center p-4 text-center">
        <div className="bg-slate-800/50 p-8 rounded-2xl border border-slate-700/50 max-w-md w-full flex flex-col items-center">
          <MailCheck className="h-16 w-16 text-primary mb-6" />
          <h1 className="text-2xl font-bold text-white mb-2">Check your email</h1>
          <p className="text-slate-300 mb-6">
            We sent a confirmation link to {email}. After you confirm your email, you&apos;ll be brought back here to finish linking this TV.
          </p>
          <div className="flex w-full flex-col gap-3">
            <button
              onClick={() => {
                setAuthMode("signin");
                void checkSession();
              }}
              className="px-6 py-2 bg-primary hover:bg-primary-hover text-white rounded-lg transition-colors"
            >
              I already confirmed
            </button>
            <button
              onClick={() => {
                setError(null);
                setStatus("needs_auth");
                setAuthMode("signin");
              }}
              className="px-6 py-2 bg-slate-700 hover:bg-slate-600 text-white rounded-lg transition-colors"
            >
              Back to sign in
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center p-4 text-center">
        <div className="bg-slate-800/50 p-8 rounded-2xl border border-slate-700/50 max-w-md w-full flex flex-col items-center">
          <XCircle className="h-16 w-16 text-red-500 mb-6" />
          <h1 className="text-2xl font-bold text-white mb-2">Could not continue</h1>
          <p className="text-slate-300 mb-6">{error}</p>
          <button
            onClick={() => window.location.reload()}
            className="px-6 py-2 bg-slate-700 hover:bg-slate-600 text-white rounded-lg transition-colors"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center p-4">
      <div className="w-full max-w-md space-y-8 bg-slate-800/50 p-8 rounded-2xl border border-slate-700/50">
        <div className="flex flex-col items-center text-center">
          <div className="bg-primary/10 p-3 rounded-full mb-4">
            <Tv className="h-8 w-8 text-primary" />
          </div>
          <h2 className="text-2xl font-bold tracking-tight text-white">
            Connect your TV account
          </h2>
          <p className="text-sm text-slate-400 mt-2">
            {authMode === "signin"
              ? "Sign in with your existing account to approve this TV login."
              : "Create a new Omnio account on your phone and link it to this TV."}
          </p>
        </div>

        <div className="grid grid-cols-2 gap-2 rounded-xl bg-slate-900/60 p-1">
          <button
            type="button"
            onClick={() => {
              setAuthMode("signin");
              setError(null);
            }}
            className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
              authMode === "signin"
                ? "bg-primary text-white"
                : "text-slate-300 hover:bg-slate-800/80"
            }`}
          >
            Sign In
          </button>
          <button
            type="button"
            onClick={() => {
              setAuthMode("signup");
              setError(null);
            }}
            className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
              authMode === "signup"
                ? "bg-primary text-white"
                : "text-slate-300 hover:bg-slate-800/80"
            }`}
          >
            Create Account
          </button>
        </div>

        <form className="mt-8 space-y-6" onSubmit={handleAuth}>
          <div className="space-y-4 rounded-md shadow-sm">
            <div>
              <label htmlFor="email" className="sr-only">
                Email address
              </label>
              <input
                id="email"
                name="email"
                type="email"
                autoComplete="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="relative block w-full rounded-lg border-0 bg-slate-900/50 py-3 px-4 text-white ring-1 ring-inset ring-slate-700 placeholder:text-slate-400 focus:z-10 focus:ring-2 focus:ring-inset focus:ring-primary sm:text-sm sm:leading-6"
                placeholder="Email address"
              />
            </div>
            <div>
              <label htmlFor="password" className="sr-only">
                Password
              </label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete={authMode === "signin" ? "current-password" : "new-password"}
                minLength={6}
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="relative block w-full rounded-lg border-0 bg-slate-900/50 py-3 px-4 text-white ring-1 ring-inset ring-slate-700 placeholder:text-slate-400 focus:z-10 focus:ring-2 focus:ring-inset focus:ring-primary sm:text-sm sm:leading-6"
                placeholder={authMode === "signin" ? "Password" : "Create a password"}
              />
            </div>
          </div>

          {authMode === "signup" && (
            <p className="text-sm text-slate-400">
              If your project requires email confirmation, we&apos;ll send you back to this TV approval page after you verify your account.
            </p>
          )}

          {error && (
            <div className="text-sm text-red-400 bg-red-400/10 p-3 rounded-lg border border-red-400/20">
              {error}
            </div>
          )}

          <div>
            <button
              type="submit"
              disabled={authLoading}
              className="group relative flex w-full justify-center rounded-lg bg-primary px-3 py-3 text-sm font-semibold text-white hover:bg-primary-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {authLoading ? (
                <Loader2 className="h-5 w-5 animate-spin" />
              ) : (
                authMode === "signin" ? "Sign in & Approve" : "Create Account"
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function TvLoginPage() {
  return (
    <Suspense fallback={
      <div className="flex min-h-screen flex-col items-center justify-center p-4">
        <Loader2 className="h-12 w-12 animate-spin text-primary mb-4" />
      </div>
    }>
      <TvLoginContent />
    </Suspense>
  );
}
