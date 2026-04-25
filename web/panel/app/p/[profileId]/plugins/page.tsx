import { listPlugins } from "@/lib/data/addons";
import PluginsForm from "@/components/forms/PluginsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function PluginsPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const plugins = await listPlugins(id);

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Plugins</h1>
        <p className="text-sm text-slate-400">
          Scraper plugins for this profile. Drag to reorder; toggle enable; rename
          for clarity. The JS code lives on the TV and is not edited from here.
        </p>
      </header>

      <PluginsForm
        profileId={id}
        initial={plugins.map((p) => ({
          id: p.id,
          url: p.url,
          name: p.name,
          enabled: p.enabled,
        }))}
      />
    </div>
  );
}
