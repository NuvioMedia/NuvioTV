import { listCollections } from "@/lib/data/collections";
import CollectionsForm from "@/components/forms/CollectionsForm";

interface Props {
  params: Promise<{ profileId: string }>;
}

export default async function CollectionsPage({ params }: Props) {
  const { profileId } = await params;
  const id = Number.parseInt(profileId, 10);
  const collections = await listCollections(id);

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Collections</h1>
        <p className="text-sm text-slate-400">
          Custom row collections you&apos;ve built on the TV. Reorder, rename, pin,
          set folder covers and shapes from here.
        </p>
      </header>

      <CollectionsForm profileId={id} initial={collections} />
    </div>
  );
}
