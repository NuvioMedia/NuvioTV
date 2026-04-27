import { useMemo } from "react";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { ArrowLeft } from "lucide-react";
import { Player } from "@/components/Player";
import { parseProfileId } from "@/lib/profileContext";
import { readHandoff } from "@/lib/streamHandoff";

interface PlayerSearch {
  src: string;
}

export const Route = createFileRoute("/p/$profileId/player/$type/$id")({
  validateSearch: (search: Record<string, unknown>): PlayerSearch => ({
    src: typeof search.src === "string" ? search.src : "",
  }),
  component: PlayerPage,
});

function PlayerPage() {
  const params = Route.useParams();
  const search = Route.useSearch();
  const navigate = useNavigate();
  const profileId = parseProfileId(params.profileId);

  const baseId = params.id.split(":")[0]!;
  const [season, episode] = parseEpisodeFromId(params.id);
  const handoff = useMemo(() => readHandoff(params.type, params.id), [params.type, params.id]);

  if (!search.src) {
    return (
      <div className="p-6 text-slate-400">
        No stream URL provided. <button onClick={() => history.back()}>Go back</button>
      </div>
    );
  }

  return (
    <div className="relative h-screen w-full bg-black">
      <button
        type="button"
        onClick={() =>
          navigate({
            to: "/p/$profileId/detail/$type/$id",
            params: { profileId: String(profileId), type: params.type, id: baseId },
          })
        }
        className="absolute left-4 top-4 z-20 flex items-center gap-1 rounded-full bg-black/60 px-3 py-1.5 text-sm text-white backdrop-blur hover:bg-black/80"
      >
        <ArrowLeft className="h-4 w-4" /> Back
      </button>
      <Player
        src={search.src}
        profileId={profileId}
        contentId={baseId}
        contentType={params.type}
        videoId={params.id}
        season={season}
        episode={episode}
        subtitles={handoff?.subtitles ?? []}
      />
    </div>
  );
}

function parseEpisodeFromId(id: string): [number | null, number | null] {
  const parts = id.split(":");
  if (parts.length < 3) return [null, null];
  const season = Number(parts[1]);
  const episode = Number(parts[2]);
  return [
    Number.isFinite(season) ? season : null,
    Number.isFinite(episode) ? episode : null,
  ];
}
