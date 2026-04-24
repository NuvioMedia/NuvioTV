import {
  Boxes,
  Eye,
  Folder,
  Library,
  Plug,
  Smartphone,
} from "lucide-react";
import StatCard from "@/components/StatCard";
import { getOverview } from "@/lib/data/overview";

interface Props {
  params: Promise<{ profileId: string }>;
}

function formatRelative(iso: string | null): string {
  if (!iso) return "never";
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 60_000) return "just now";
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m ago`;
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}h ago`;
  return `${Math.floor(ms / 86_400_000)}d ago`;
}

export default async function ProfileHome({ params }: Props) {
  const { profileId } = await params;
  const idx = Number.parseInt(profileId, 10);
  const overview = await getOverview(idx);
  const base = `/p/${idx}`;

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Overview</h1>
        <p className="text-sm text-slate-400">
          Settings last synced {formatRelative(overview.settingsUpdatedAt)}.
        </p>
      </header>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        <StatCard label="Addons" value={overview.addons} icon={Boxes} href={`${base}/addons`} />
        <StatCard label="Plugins" value={overview.plugins} icon={Plug} href={`${base}/plugins`} />
        <StatCard
          label="Collections"
          value={overview.collections}
          icon={Folder}
          href={`${base}/collections`}
        />
        <StatCard
          label="Library"
          value={overview.libraryItems}
          icon={Library}
          href={`${base}/library`}
        />
        <StatCard label="Watched" value={overview.watchedItems} icon={Eye} hint="all-time" />
        <StatCard
          label="Linked devices"
          value={overview.linkedDevices}
          icon={Smartphone}
          href={`${base}/devices`}
        />
      </div>

      <div className="rounded-2xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-100">
        <strong className="font-semibold">Read-only preview.</strong> v1 ships viewing only —
        editing lands in v2. The values below come straight from your TV&apos;s last sync.
      </div>
    </div>
  );
}
