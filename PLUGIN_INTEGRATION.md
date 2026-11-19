# NuvioTV plugin and scraper integration

This document describes the JavaScript scraper repository format used by the TV app: how a 
repository manifest points to provider scripts, how the app calls those scripts, and how provider 
results and settings are represented.

This is a NuvioTV-specific contract. It is not the Stremio Add-on HTTP protocol: NuvioTV downloads 
JavaScript files from a manifest and executes `getStreams` inside the app within a restricted 
JavaScript sandbox.

## Repository layout

A repository can be hosted as static files. The app starts from a URL to `manifest.json` 
(or appends `/manifest.json` when given a repository base URL). Each scraper entry points to one 
JavaScript file. Relative filenames are resolved from the directory containing the manifest; 
an absolute `http(s)` filename is used directly.

```text
my-scrapers/
├── manifest.json
└── providers/
    └── example.js
```

The app downloads each listed scraper when the repository is added or refreshed. 
A scraper's installed ID is namespaced by its repository, so different repositories can use the 
same manifest scraper ID without sharing settings or local storage.

## JavaScript repository manifest

The TV app's Nuvio JavaScript manifest is a JSON object with repository metadata and a `scrapers` array:

```json
{
  "name": "Example Scrapers",
  "version": "1.0.0",
  "description": "A small collection of example providers.",
  "author": "Example Maintainer",
  "scrapers": [
    {
      "id": "example",
      "name": "Example Provider",
      "description": "Finds a playable stream for a movie or episode.",
      "version": "1.0.0",
      "filename": "providers/example.js",
      "supportedTypes": ["movie", "tv"],
      "enabled": true,
      "logo": "https://example.org/provider.png",
      "contentLanguage": ["en"],
      "formats": ["mp4", "mkv"],
      "hasSettings": true
    }
  ]
}
```

### Repository fields

| Field         | Required | Meaning                                                         |
|---------------|----------|-----------------------------------------------------------------|
| `name`        | Yes      | Repository name shown by the app.                               |
| `version`     | Yes      | Repository version string.                                      |
| `scrapers`    | Yes      | Array of scraper definitions. Use `[]` for an empty repository. |
| `description` | No       | Optional repository description.                                |
| `author`      | No       | Optional author metadata.                                       |

### Scraper fields

| Field             | Required | Meaning                                                                                                       |
|-------------------|----------|---------------------------------------------------------------------------------------------------------------|
| `id`              | Yes      | Stable ID unique within this repository. The app combines it with the repository ID.                          |
| `name`            | Yes      | Display name and provider label in stream results.                                                            |
| `version`         | Yes      | Scraper version string.                                                                                       |
| `filename`        | Yes      | Relative path beside the manifest, or an absolute `http(s)` URL, to the JavaScript file.                      |
| `description`     | No       | Short description displayed with the scraper.                                                                 |
| `supportedTypes`  | No       | Media types the scraper handles; defaults to `["movie", "tv"]`. `series` is also recognized for episodic TV.  |
| `enabled`         | No       | Initial enabled state; defaults to `true`. Users can change it in the app.                                    |
| `logo`            | No       | Provider image URL.                                                                                           |
| `contentLanguage` | No       | Language metadata retained with the installed scraper.                                                        |
| `formats`         | No       | Format metadata retained with the installed scraper.                                                          |
| `hasSettings`     | No       | Set to `true` when the script exports `onSettings()` and should show a Configure action. Defaults to `false`. |

The parser accepts some additional fields used by other Nuvio provider templates, but TV does not 
currently apply platform availability or external-player hints. Do not rely on fields such 
as `supportedPlatforms`, `disabledPlatforms`, `supportedFormats`, `supportsExternalPlayer`, or 
`limited` to filter a scraper in the TV app.

## Provider entry points

The script is evaluated as a CommonJS-style module. Export `getStreams`; export `onSettings` when 
the scraper has configurable inputs. The app accepts these functions from `module.exports` 
(or as globals for older scripts).

```js
async function getStreams(tmdbId, mediaType, season, episode) {
  // Return an array of stream objects. Return [] when no stream is available.
  return [];
}

function onSettings() {
  return [];
}

module.exports = { getStreams, onSettings };
```

`getStreams(tmdbId, mediaType, season, episode)` receives the TMDB ID as a string, the media type, 
and nullable season and episode numbers. A movie call has null season and episode. 

For episodic content the app normally passes `tv`; when the scraper declares `series` in
`supportedTypes`, it passes `series` for episodic calls.

A provider should return a promise or an array; asynchronous functions are awaited.

The runtime provides: `fetch`, `console`, `global`, `window`, `self`, `SCRAPER_ID`, `SCRAPER_SETTINGS`, `TMDB_API_KEY`, 
and a Cheerio-like `cheerio.load(html)` helper. 

The runtime does not currently provide a Node.js package loader, so bundle imports into the file 
referenced by `filename` before publishing.

## Stream result format

Return an array of objects. Each object describes one playable or externally resolvable source. The app parses these fields:

| Field              | Type              | Meaning                                                                                                                           |
|--------------------|-------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `name`             | string            | Source/provider name.                                                                                                             |
| `title`            | string            | Stream label or description; falls back to `name`, then `Unknown`.                                                                |
| `url`              | string            | Direct stream URL, such as HLS or MP4.                                                                                            |
| `externalUrl`      | string            | Link handled outside the direct player flow. `url` must be empty.                                                                 |
| `ytId`             | string            | YouTube video ID.                                                                                                                 |
| `quality`          | string            | Display quality, such as `1080p`.                                                                                                 |
| `size`             | string            | Optional file-size label.                                                                                                         |
| `language`         | string            | Audio/source language label.                                                                                                      |
| `type`             | string            | Optional source type metadata.                                                                                                    |
| `headers`          | object of strings | Request headers used for playback, often `Referer` or `User-Agent`.                                                               |
| `seeders`, `peers` | number            | Torrent availability metadata.                                                                                                    |
| `infoHash`         | string            | Torrent info hash.                                                                                                                |
| `fileIdx`          | integer           | File index for a torrent result.                                                                                                  |
| `subtitles`        | array             | Stream-provided subtitle entries, described below.                                                                                |
| `behaviorHints`    | object            | [Stremio behavior hints](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md), described below. |
| `provider`         | string            | Provider ID.                                                                                                                      |

Provide at least one usable locator (`url`, `externalUrl`, `ytId`, or `infoHash`) for each result.

```js
{
  name: "Example Provider",
  title: "1080p WEB-DL",
  url: "https://cdn.example.org/video.m3u8",
  quality: "1080p",
  headers: {
    Referer: "https://example.org/",
    "User-Agent": "Example scraper"
  },
  subtitles: [
    {
      id: "en",
      url: "https://cdn.example.org/subtitles/en.vtt",
      language: "en",
      name: "English",
      headers: {
        Referer: "https://example2.org/",
        "User-Agent": "Example scraper 2"
      },
    }
  ],
  behaviorHints: {
     bingeGroup: "specific-provider-id|1080",
     videoHash: "8e245d9679d31e12",
     videoSize: 10000000,
     filename: "Video name [WEB-DL] 2026.mp4",
     audioDelayMs: 250,
     audioDelayLanguage: "en",
  },
}
```

For a torrent result, return `infoHash` and optionally `fileIdx`.

You may also point the `url` to a URL structure along the lines of:
* `torrent://{infoHash}`,
* `torrent://{infoHash}/{fileIdx}`, or
* `magnet:?xt=urn:btih:{infoHash}&dn={fileName}`

For a YouTube result, use `ytId`.

A result does not need to include all fields.

### Behavior Hints object

| Field                | Type   | Meaning                                                                                                                                                        |
|----------------------|--------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `bingeGroup`         | string | If defined, videos with this group within the addon will be chosen automatically for binge watching.                                                           |
| `videoHash`          | string | [OpenSubtitles file hash](https://web.archive.org/web/20240820081059/https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes) for the video |
| `videoSize`          | long   | Size of the video file in bytes.                                                                                                                               |
| `filename`           | string | Filename of the video file.                                                                                                                                    |
| `audioDelayMs`       | number | Audio delay in milliseconds to automatically apply.                                                                                                            |
| `audioDelayLanguage` | string | The language to apply the audio delay for.                                                                                                                     |

### Subtitle object

| Field              | Type              | Meaning                                                             |
|--------------------|-------------------|---------------------------------------------------------------------|
| `url`              | string            | URL for the subtitle - *required*.                                  |
| `language`, `lang` | string            | The subtitle language (defaults to `Unknown`).                      |
| `name`             | string            | The subtitle name  (defaults to `Plugin`).                          |
| `headers`          | object of strings | Request headers used for playback, often `Referer` or `User-Agent`. |
| `id`               | string            | Unique identifier for the subtitle (falls back the `url`).          |

## Settings and saved values

When `hasSettings` is `true` in the scraper manifest, the app evaluates the exported `onSettings()`
function to build the Configure dialog. It may be synchronous or asynchronous and returns an array 
of field descriptions. 
Values are stored per installed scraper and supplied to the provider as `SCRAPER_SETTINGS`.

```js
function onSettings() {
  return [
    {
      key: "api-token",
      type: "text",
      label: "API token",
      description: "Optional account token.",
      placeholder: "Token",
      defaultValue: "",
      isPassword: true
    },
    {
      key: "quality",
      type: "select",
      label: "Preferred quality",
      options: [
        { label: "1080p", value: "1080p" },
        { label: "720p", value: "720p" }
      ],
      defaultValue: "1080p"
    },
    {
      key: "include-cam",
      type: "toggle",
      label: "Include CAM sources",
      defaultValue: false
    }
  ];
}

async function getStreams(tmdbId, mediaType, season, episode) {
  const settings = globalThis.SCRAPER_SETTINGS || {};
  const quality = settings.quality || "1080p";
  const token = settings["api-token"] || "";
  // Use settings when building the provider request.
  return [];
}
```

Supported field types are:

| Type     | Fields used by the dialog                                                                                       |
|----------|-----------------------------------------------------------------------------------------------------------------|
| `header` | `label`                                                                                                         |
| `info`   | `label`, optional `description`                                                                                 |
| `text`   | `key`, `label`, optional `description`, `placeholder`, `defaultValue`, `isPassword`                             |
| `select` | `key`, `label`, optional `description`, `defaultValue`, and `options` containing `{ "label", "value" }` entries |
| `toggle` | `key`, `label`, optional `description`, `defaultValue` (boolean)                                                |

Value fields (`text`, `select`, and `toggle`) need a stable, non-empty `key`. Saved and default 
values are keyed by that string in `SCRAPER_SETTINGS`.
Use JSON-compatible scalar defaults (string, number, boolean, or null); a select default should 
match one of its option values.

## `localStorage`

The runtime provides a scraper-scoped `localStorage` shim with 
`getItem`, `setItem`, `removeItem`, `clear`, `key`, and `length`, plus property access and 
enumeration.

Values are strings, like browser storage. Data persists between scraper runs and is deleted when
its repository is removed.

```js
const lastRun = localStorage.getItem("last-run");
localStorage.setItem("last-run", new Date().toISOString());
```

This is persistent storage for small scraper state, not a general database. Do not store large 
response bodies or secrets that should not be kept on the device.

## How the pieces connect

1. The user adds a repository URL. The app fetches its `manifest.json` and each scraper file listed in `scrapers`.
2. The app stores each scraper's metadata and JavaScript locally. User enabled state and settings are associated with that installed scraper.
3. For a matching media type, the app invokes `getStreams` with the TMDB ID and optional episode coordinates. It supplies `SCRAPER_SETTINGS` and the scraper's persisted `localStorage`.
4. The returned array is converted into app stream objects. The manifest scraper name is used as the provider label; URL headers, torrent identifiers, YouTube IDs, and subtitles are carried through when present.
5. When the repository is removed, the app removes its scrapers and their associated local code and local storage.

## Other repository format

The app also recognizes CloudStream-style external repositories. Those use a repository JSON object 
with `name`, optional `description`, `manifestVersion`, and `pluginLists`; the listed plugin JSON 
documents point to `.cs3` extensions.

These are native extension packages, not JavaScript providers, and do not use the Nuvio 
`scrapers` manifest or `getStreams` contract.
