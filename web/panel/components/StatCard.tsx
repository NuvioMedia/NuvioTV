import Link from "next/link";
import type { LucideIcon } from "lucide-react";

interface Props {
  label: string;
  value: string | number;
  icon: LucideIcon;
  href?: string;
  hint?: string;
}

export default function StatCard({ label, value, icon: Icon, href, hint }: Props) {
  const inner = (
    <>
      <div className="flex items-center justify-between">
        <span className="text-sm text-slate-400">{label}</span>
        <Icon className="h-4 w-4 text-slate-500" />
      </div>
      <div className="mt-2 text-2xl font-semibold">{value}</div>
      {hint && <div className="mt-1 text-xs text-slate-500">{hint}</div>}
    </>
  );

  const className =
    "block rounded-xl border border-slate-700/50 bg-slate-800/40 p-4 transition" +
    (href ? " hover:border-primary" : "");

  return href ? (
    <Link href={href} className={className}>
      {inner}
    </Link>
  ) : (
    <div className={className}>{inner}</div>
  );
}
