export interface Profile {
  id: string;
  user_id: string;
  profile_index: number;
  name: string;
  avatar_color_hex: string;
  uses_primary_addons: boolean;
  uses_primary_plugins: boolean;
  avatar_id: string | null;
  pin_hash: string | null;
  created_at: string;
  updated_at: string;
}

export interface Addon {
  id: string;
  url: string;
  name: string | null;
  enabled: boolean;
  sort_order: number;
  profile_id: number;
  user_id: string;
  created_at: string;
  updated_at: string;
}

export interface WatchProgressRow {
  id: string;
  content_id: string;
  content_type: string;
  video_id: string;
  season: number | null;
  episode: number | null;
  position: number;
  duration: number;
  last_watched: number;
  progress_key: string;
  profile_id: number;
  updated_at: string;
}

export interface LibraryItem {
  id: string;
  content_id: string;
  content_type: string;
  name: string;
  poster: string | null;
  poster_shape: string;
  background: string | null;
  description: string | null;
  release_info: string | null;
  imdb_rating: number | null;
  genres: string[];
  addon_base_url: string | null;
  added_at: number;
  profile_id: number;
  updated_at: string;
}

export interface WatchedItem {
  id: string;
  content_id: string;
  content_type: string;
  title: string;
  season: number | null;
  episode: number | null;
  watched_at: number;
  profile_id: number;
  updated_at: string;
}
