# Built-in Usenet sources

In **Settings → Usenet**, enable **Search built-in indexers**, add an NNTP
provider, and add a Newznab-compatible indexer. No stream addon is needed for
this source. Existing addon sources can still be used alongside it.

Providers accept a host, port, TLS setting, username, password and connection
allowance. Add multiple providers for missing-article failover. The existing
global maximum-connections setting can further limit their combined allowance.
For a private/local NNTP server, enable **Allow self-hosted servers** in the
performance settings.

## Source hierarchy

Every provider and indexer has a priority from 1 (highest) to 5. The list is
always ordered by priority; **Move up/down** changes the order within one
priority.

- **Providers with the same priority are load balanced.** Article traffic is
  shared by available connection capacity, then fails over through the rest of
  that priority in list order.
- **Lower-priority providers are backups.** They only receive articles that
  every higher-priority provider is missing or failed to serve, so a block
  account behind an unlimited one is only charged for those articles. They
  open no idle connections in advance.
- **Indexers are all searched by default**, and their results merged. Their
  priority only matters through two options, both off by default:
  - **Hide repeated releases** shows a release found by several indexers once:
    the copy from the highest priority (then list order) that passes the
    filters. Names are compared ignoring case and punctuation.
  - **Search lower priorities only as fallback** searches equal priorities
    together and queries lower ones only when the higher ones produced no
    result that passes the filters (including when they failed). This saves
    API hits on limited indexers.

For providers, keep everything at priority 1 to balance them, or give each its
own priority for a strict preference order. Providers pass their priority to the
engine as `?priority=N` on the server URL. Lower values are preferred, and a
server without one counts as 0. Addon-supplied servers can use the same
parameter.

Indexers accept a complete API endpoint and API key. Examples:

- `https://indexer.example/api`
- `http://hydra.local:5076/api`
- `http://prowlarr.local:9696/1/api` (the individual indexer's Newznab endpoint)

Use **Test provider** to connect, negotiate TLS and sign in with the entered
settings before saving. It applies the same local-address rule as playback, and
reports unreachable hosts, TLS failures, rejected credentials and refused
connections (such as a reached connection limit) separately. Use **Test
indexer** to check an indexer's capabilities. Each source can be edited,
disabled, deleted, reprioritized or reordered. Credentials are stored as AES-GCM
ciphertext protected by Android Keystore, separately for each profile. They
are device-local and are not included in account/profile synchronization.

Searches use advertised movie/TV capabilities, preferring IMDb/TMDB IDs.
Title searches are used when the indexer does not support an available ID;
they require TMDB metadata. Series searches require a season and episode,
including season zero for specials. Contradictory episode filenames are
removed; season packs remain playable through the engine's episode selection.
Unsupported media identifiers without a TMDB mapping cannot be searched.

The **Built-in Usenet** result group updates as indexers finish, independently
of addon/plugin searches. Sort by resolution, size, age or indexer priority.
Filters cover minimum resolution, maximum size, maximum age, CAM/screener
releases and result count. Passworded NZBs are always excluded. Unknown sizes
or dates are excluded when their corresponding maximum filter is enabled.
Duplicate download URLs are removed while alternative indexer URLs remain
available for playback fallback.

Searches have a 25-second timeout per request and fetch at most two pages of
100 results per indexer. NZBs are fetched only when the existing playback or
opt-in prefetch path needs them. Failed indexers do not cancel successful
sources. Configuration changes invalidate the stream search session cache.

## Saving indexer API hits

- **Search results** are reused for 15 minutes by the stream search session
  cache. They are not kept longer, so new releases show up.
- **Capabilities** (`t=caps`) are stored on the device for 7 days and survive
  restarts. An edited URL or API key fetches them again. When a refresh fails,
  the expired copy is used. **Test indexer** always makes a live request.
- **Limits:** an indexer that throttles (HTTP 429) or reports a spent quota
  (Newznab error 500/501) is paused without further requests: for its
  `Retry-After`, otherwise 60 seconds for throttling (at most 15 minutes) and
  30 minutes for a spent quota. A response announcing zero remaining API hits
  or grabs (`X-RateLimit-Daily-Remaining`, `x-api-remaining`,
  `X-DNZBLimit-Daily-Remaining`, `x-grab-remaining`) pauses it for 15 minutes.
  Pauses survive restarts. A paused indexer counts as failed, so lower
  priorities are searched instead.
- **NZBs** are cached by the engine for 14 days, so playing a release again does
  not grab it again.

This state is device-local, keyed by a hash of the endpoint and key, and holds
no credentials.

Protocol references (implementation is native Kotlin):

- [Newznab Web API](https://newznab.readthedocs.io/en/latest/misc/api.html)
- [AIOStreams Newznab source](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/builtins/newznab/addon.ts)
