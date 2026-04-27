import { supabase } from "./supabase";

export interface ProgressKeyArgs {
  contentId: string;
  contentType: string;
  videoId?: string;
  season?: number | null;
  episode?: number | null;
}

// Mirrors the Android client's progress_key shape so rows merge cleanly.
export function buildProgressKey(args: ProgressKeyArgs): string {
  const parts = [args.contentType, args.contentId];
  if (args.season != null) parts.push(`s${args.season}`);
  if (args.episode != null) parts.push(`e${args.episode}`);
  if (args.videoId && args.videoId !== args.contentId) parts.push(args.videoId);
  return parts.join(":");
}

export async function upsertWatchProgress(input: {
  profileId: number;
  contentId: string;
  contentType: string;
  videoId: string;
  position: number;
  duration: number;
  season?: number | null;
  episode?: number | null;
}) {
  const progressKey = buildProgressKey(input);
  const now = Date.now();

  const { data: userResp } = await supabase.auth.getUser();
  const userId = userResp.user?.id;
  if (!userId) return;

  const { error } = await supabase.from("watch_progress").upsert(
    {
      user_id: userId,
      profile_id: input.profileId,
      content_id: input.contentId,
      content_type: input.contentType,
      video_id: input.videoId,
      season: input.season ?? null,
      episode: input.episode ?? null,
      position: Math.floor(input.position),
      duration: Math.floor(input.duration),
      last_watched: now,
      progress_key: progressKey,
    },
    { onConflict: "user_id,profile_id,progress_key" }
  );

  if (error) {
    console.warn("watch_progress upsert failed", error);
  }
}

export async function recordWatchedItem(input: {
  profileId: number;
  contentId: string;
  contentType: string;
  title: string;
  season?: number | null;
  episode?: number | null;
}) {
  const { data: userResp } = await supabase.auth.getUser();
  const userId = userResp.user?.id;
  if (!userId) return;

  const { error } = await supabase.from("watched_items").upsert(
    {
      user_id: userId,
      profile_id: input.profileId,
      content_id: input.contentId,
      content_type: input.contentType,
      title: input.title,
      season: input.season ?? null,
      episode: input.episode ?? null,
      watched_at: Date.now(),
    },
    { onConflict: "user_id,profile_id,content_id,season,episode" }
  );

  if (error) console.warn("watched_items upsert failed", error);
}
