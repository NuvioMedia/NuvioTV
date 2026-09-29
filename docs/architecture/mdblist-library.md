# MDBList Library support

MDBList can be selected independently as the Library Source in Settings → Trackers. Library displays the connected account’s watchlist and owned static movie/show lists. The shared membership picker can add or remove a title from MDBList alongside local, Trakt and Simkl destinations. Each remote destination shows its provider name so two watchlists or identically named lists remain distinguishable.

## Supported behavior

- Browse and filter watchlist and owned static lists with the existing Library controls.
- Add/remove movies and shows, including series supplied by anime metadata sources when an external ID is available.
- Create private or public static lists; rename them, change visibility, and delete them through Manage Lists.
- Preserve per-list item order when a title belongs to several lists.
- Restore cached contents across app launches, isolate profiles/accounts, and retain the last complete cache when synchronization fails.

Dynamic, feed, official, external and season/episode lists are excluded from this scope. MDBList has no documented list-description editing or list-reordering endpoint, so those controls are hidden. Trakt retains its description, privacy and ordering features. List item responses do not consistently include an added timestamp; added-date sorting uses it when supplied and otherwise falls back to stable ordering. Ratings enrichment remains the separate existing API-key feature.

## Shared architecture

`TrackingLibraryProvider` already provides browsing, membership and refresh contracts. Its optional `TrackingListManager` now provides list-management operations and capabilities. The Library repository and dialogs select that manager; they no longer call Trakt directly for list CRUD. `LibraryListPrivacy` replaces the Trakt-specific type name without changing its wire values. Existing library-source names retain their stored meaning; `MDBLIST` is appended.

TV has its existing tracking ports and no dependency on the mobile Compose application. MDBList’s library business logic uses the same TV ports, identity models, HTTP client and account-scoped sync repository as the existing tracker implementation. No duplicate platform authentication, profile storage or network retry policy was introduced.

- `MdbListLibraryRemote`: owned-list metadata, cursor/offset paging and version-based reuse.
- `MdbListLibraryDecoder`: strict static-list classification and mixed/legacy item response decoding.
- `MdbListLibraryProjection`: external-ID aliases, typed movie/show identity, memberships and per-list ranks.
- `MdbListLibraryWriter`: validated write receipts, scoped CRUD and membership changes.
- `MdbListLibraryService`: lazy refresh, cached membership reads, coalescing and retry gates.
- `MdbListTrackingLibraryProvider`: registration in the shared source/membership system.

Library data is an optional, backwards-compatible field in `MdbListSyncSnapshot`, persisted through the existing profile storage. Every write captures an OAuth scope and checks it around HTTP and persistence. Successful changes update the cached snapshot without re-fetching the whole library. Each membership destination is committed separately, preserving completed writes if a later destination fails. Uncertain writes mark the cache invalid for a later read reconciliation; POST/PUT/DELETE are not retried after ambiguous server or transport failures.

Management dialogs capture their profile, source and dialog instance. Switching profiles or sources closes the editor. Duplicate submissions are suppressed, and completion of an old request cannot close a newly opened editor. Repository routing also validates the source, profile and list-key namespace.

## API contract and request budget

Sources reviewed: the [current OpenAPI schema](https://api.mdblist.com/schema/), [API reference](https://api.mdblist.com/docs/), [authentication guide](https://api.mdblist.com/docs/authentication/), and the full local documentation snapshot under `docs/mdblist/`. Live contract checks were performed on 6 September 2026 using the registered Device Code account.

| Operation | Endpoint / behavior |
|---|---|
| Owned lists | `GET /lists/user?unified=false&sort=ranked`; include explicitly static lists owned by the current account |
| Watchlist | `GET /watchlist/items` |
| Static contents | `GET /lists/{id}/items` |
| Membership changes | `POST /watchlist/items/{add,remove}` uses nested `ids`; `POST /lists/{id}/items/{add,remove}` uses direct provider IDs |
| Create | `POST /lists/user/add` with `name` and `private` |
| Rename/visibility | `PUT /lists/{id}` with `name` and `private` |
| Delete | `DELETE /lists/{id}`; require a successful confirmation or HTTP 204 |

The live service returns `ids: [id]` for unified metadata, an array for list-info reads, and nested per-media mutation counts for static lists. A create response can omit the name. Static item mutations update `last_updated_at`. Parsers preserve these observed differences from the incomplete schema.

Reads request up to 1,000 items per page with bundled posters, descriptions and genres. All pages must succeed before publishing. Repeated cursors, missing continuation data, or malformed items fail the refresh rather than truncating the library.

| Situation | Library requests, excluding account lookup, token refresh and retries |
|---|---:|
| Restore fresh cache or revisit Library | 0 |
| First load with N single-page static lists | 2 + N |
| Unchanged refresh after the 15-minute interval | 2: list metadata and watchlist |
| Changed static list | One additional request per page of that list |
| Missing version or invalidated cache | Re-read affected/all static contents |
| Add/remove one title in one destination | 1 write when membership changes |
| Create, rename, change visibility or delete | 1 write each with an initialized cache |

Refreshes are triggered by consumers and user actions, not a background timer. Overlapping refreshes coalesce. Failed automatic reads back off for 60 seconds; quota errors honor the API reset. Per-title MDBList lookups are not used for Library metadata.

A planning estimate for one TV, 2–4 episodes per day and a few cached lists is roughly 30–80 total tracking/library requests daily. This is not usage telemetry or a guarantee. Initial imports, pagination, additional devices and manual refreshes add requests. The separate ratings integration can add one request per enabled rating source per uncached title (for example, 20 titles × 6 sources = 120 requests). The API’s headers and `/user` expose actual usage and allowance.

## Validation

The full JVM regression completed 1,356 tests: 1,342 passed, 13 failed and one was skipped. All 13 failing test identities match the failures already reproduced on revision `23d1fe478`; no new failures were introduced. The recorded comparison is in `docs/mdblist/validation/library/full-jvm/summary.json`. The final focused JVM run passed all 220 tests with no failures or skips. Both the full debug APK/instrumentation build and Play Store debug Kotlin compilation passed. The cross-variant check also caught and fixed a nullable OkHttp response-body compatibility issue. Raw local evidence is retained under `docs/mdblist/validation/library/`; it contains no OAuth tokens. Live tests use a temporary private list, remove their test membership additions and verify list deletion. The live checks are gated by explicit instrumentation arguments, so ordinary test runs do not change a real account.

The normal signed full debug APK also built successfully with the repository’s original non-debuggable settings. Live library reads and mutations returned successful responses, watchlist changes were restored, and a final metadata read confirmed the temporary static list was absent. Media source links were checked against MDBList’s canonical redirects.

All six Compose UI checks passed on API 36 Android TV. They cover supported fields/privacy, Trakt compatibility, empty-list Create focus, pending-action disabling, provider-labelled watchlist navigation, and D-pad selection. The separately gated live Library lifecycle check passed on the same emulator, including movie/show membership, rename, watchlist add/remove, readback, and deletion cleanup.

After validation, the normal signed APK was restored and verified without the `DEBUGGABLE` flag. The instrumentation package was removed and the test emulator was stopped. The curated architecture document is intentionally committed despite the repository’s general `docs/` ignore rule; downloaded provider documentation and raw QA artifacts remain local.
