"use client";

import { useEffect, useState, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { supabase } from "../../lib/supabase";
import { Tv, Loader2, CheckCircle2, XCircle } from "lucide-react";

function TvLoginContent() {
  const searchParams = useSearchParams();
  const code = searchParams.get("code");

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(true);
  const [authLoading, setAuthLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<"checking" | "needs_auth" | "approving" | "success" | "error">("checking");
  const [session, setSession] = useState<any>(null);

  useEffect(() => {
    checkSession();
  }, []);

  useEffect(() => {
    if (session && status === "needs_auth") {
      approveCode();
    }
  }, [session, status]);

  const checkSession = async () => {
    try {
      const { data: { session } } = await supabase.auth.getSession();
      setSession(session);
      
      if (!code) {
        setError("No login code provided. Please scan the QR code on your TV again.");
        setStatus("error");
        setLoading(false);
        return;
      }

      if (session) {
        setStatus("approving");
        await approveCode();
      } else {
        setStatus("needs_auth");
        setLoading(false);
      }
    } catch (err: any) {
      setError(err.message || "Failed to check session");
      setStatus("error");
      setLoading(false);
    }
  };

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthLoading(true);
    setError(null);

    try {
      const { data, error } = await supabase.auth.signInWithPassword({
        email,
        password,
      });

      if (error) throw error;
      
      setSession(data.session);
      setStatus("approving");
    } catch (err: any) {
      setError(err.message || "Invalid email or password");
      setAuthLoading(false);
    }
  };

  const approveCode = async () => {
    if (!code) return;
    
    try {
      const { error } = await supabase.rpc("approve_tv_login_session", {
        p_code: code,
      });

      if (error) throw error;

      setStatus("success");
    } catch (err: any) {
      setError(err.message || "Failed to approve TV login. The code might be expired.");
      setStatus("error");
    } finally {
      setLoading(false);
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
            You have successfully signed in to Omnio TV. You can now close this window and return to your TV.
          </p>
        </div>
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center p-4 text-center">
        <div className="bg-slate-800/50 p-8 rounded-2xl border border-slate-700/50 max-w-md w-full flex flex-col items-center">
          <XCircle className="h-16 w-16 text-red-500 mb-6" />
          <h1 className="text-2xl font-bold text-white mb-2">Login Failed</h1>
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
            Sign in to Omnio TV
          </h2>
          <p className="text-sm text-slate-400 mt-2">
            Enter your credentials to approve the TV login
          </p>
        </div>

        <form className="mt-8 space-y-6" onSubmit={handleLogin}>
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
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="relative block w-full rounded-lg border-0 bg-slate-900/50 py-3 px-4 text-white ring-1 ring-inset ring-slate-700 placeholder:text-slate-400 focus:z-10 focus:ring-2 focus:ring-inset focus:ring-primary sm:text-sm sm:leading-6"
                placeholder="Password"
              />
            </div>
          </div>

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
                "Sign in & Approve"
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
