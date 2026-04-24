import { Suspense } from "react";
import LoginForm from "./LoginForm";

export const metadata = {
  title: "Sign in — NuvioTV Panel",
};

export default function LoginPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col items-center justify-center p-6">
      <div className="w-full rounded-2xl border border-slate-700/50 bg-slate-800/40 p-8 shadow-xl">
        <h1 className="mb-2 text-2xl font-semibold">NuvioTV Panel</h1>
        <p className="mb-6 text-sm text-slate-400">
          Sign in with the same account you use on your TV.
        </p>
        <Suspense fallback={<div className="text-slate-400">Loading…</div>}>
          <LoginForm />
        </Suspense>
      </div>
    </main>
  );
}
