# Built-in Usenet sources

In **Settings → Usenet**, enable **Search built-in indexers**, add an NNTP
provider, and add a Newznab-compatible indexer. No stream addon is needed for
this source. Existing addon sources can still be used alongside it.

Providers accept a host, port, TLS setting, username, password and connection
allowance. Add multiple providers for missing-article failover. Article traffic
is shared according to available connection capacity; provider ordering controls
the subsequent failover traversal. The existing global maximum-connections setting
can further limit their combined allowance. For a private/local NNTP server,
enable **Allow self-hosted servers** in the performance settings.

Indexers accept a complete API endpoint and API key. Examples:

- `https://indexer.example/api`
- `http://hydra.local:5076/api`
- `http://prowlarr.local:9696/1/api` (the individual indexer's Newznab endpoint)

Use **Test indexer** to check its capabilities. Each source can be edited,
disabled, deleted or moved in priority. Credentials are stored as AES-GCM
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

Protocol references (implementation is native Kotlin):

- [Newznab Web API](https://newznab.readthedocs.io/en/latest/misc/api.html)
- [AIOStreams Newznab source](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/builtins/newznab/addon.ts)
