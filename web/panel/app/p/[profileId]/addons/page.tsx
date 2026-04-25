import { listAddons } from "@/lib/data/addons";
import AddonsForm from "@/components/forms/AddonsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function AddonsPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const addons = await listAddons(id);

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Addons</h1>
        <p className="text-sm text-slate-400">
          Stremio-compatible addons installed for this profile. Drag to reorder, paste
          a manifest URL to add one. The TV writes only URL and order — name is
          discovered from the manifest on the TV.
        </p>
      </header>

      <AddonsForm
        profileId={id}
        initial={addons.map((a) => ({ id: a.id, url: a.url, name: a.name }))}
      />
    </div>
  );
}
