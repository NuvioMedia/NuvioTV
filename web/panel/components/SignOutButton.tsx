import { LogOut } from "lucide-react";
import { signOut } from "@/app/login/actions";

export default function SignOutButton() {
  return (
    <form action={signOut}>
      <button
        type="submit"
        className="flex items-center gap-2 rounded-lg border border-slate-700/50 bg-slate-800/40 px-3 py-2 text-sm text-slate-300 transition hover:border-slate-600 hover:text-slate-50"
      >
        <LogOut className="h-4 w-4" />
        Sign out
      </button>
    </form>
  );
}
