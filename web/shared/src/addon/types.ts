// Stremio addon protocol types — minimal, only what the web client consumes.
// Spec: https://github.com/Stremio/stremio-addon-sdk

export type ContentType = "movie" | "series" | "channel" | "tv" | "other";

export interface AddonManifest {
  id: string;
  version: string;
  name: string;
  description?: string;
  resources: (string | { name: string; types?: string[]; idPrefixes?: string[] })[];
  types: string[];
  catalogs: AddonCatalog[];
  idPrefixes?: string[];
  background?: string;
  logo?: string;
  contactEmail?: string;
}

export interface AddonCatalog {
  type: string;
  id: string;
  name: string;
  extra?: AddonCatalogExtra[];
  extraSupported?: string[];
  extraRequired?: string[];
  genres?: string[];
}

export interface AddonCatalogExtra {
  name: string;
  isRequired?: boolean;
  options?: string[];
  optionsLimit?: number;
}

export interface MetaPreview {
  id: string;
  type: string;
  name: string;
  poster?: string;
  posterShape?: "poster" | "square" | "landscape";
  background?: string;
  logo?: string;
  description?: string;
  releaseInfo?: string;
  imdbRating?: string;
  genres?: string[];
  runtime?: string;
  year?: number;
}

export interface MetaDetail extends MetaPreview {
  cast?: string[];
  director?: string[];
  writer?: string[];
  awards?: string;
  country?: string;
  language?: string;
  trailers?: { source: string; type: string }[];
  videos?: MetaVideo[];
  links?: { name: string; category: string; url: string }[];
}

export interface MetaVideo {
  id: string;
  title: string;
  released?: string;
  season?: number;
  episode?: number;
  overview?: string;
  thumbnail?: string;
  streams?: AddonStream[];
}

export interface AddonStream {
  url?: string;
  ytId?: string;
  infoHash?: string;
  fileIdx?: number;
  externalUrl?: string;
  name?: string;
  title?: string;
  description?: string;
  subtitles?: { url: string; lang: string }[];
  behaviorHints?: {
    notWebReady?: boolean;
    bingeGroup?: string;
    countryWhitelist?: string[];
    proxyHeaders?: { request?: Record<string, string>; response?: Record<string, string> };
    videoHash?: string;
    videoSize?: number;
    filename?: string;
  };
}

export interface CatalogResponse {
  metas: MetaPreview[];
}

export interface MetaResponse {
  meta: MetaDetail;
}

export interface StreamResponse {
  streams: AddonStream[];
}
