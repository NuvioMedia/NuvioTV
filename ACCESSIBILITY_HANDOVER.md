# Accessibility handover

## Current status

- The `nuviotv-accessibility` branch was fast-forwarded from `1a132cb05` to upstream `dev` at `4a91028b3` (`1.1.0-beta.4`) on 2026-10-04. The dirty accessibility patch reapplied without Git conflicts.
- The device still has the older `fullDebug` build version `1.0.0` (`1062`) installed as `com.nuviodebug.com`; the updated source has not been packaged or installed yet.
- TalkBack remains enabled. It was not disabled or reconfigured.
- The installed APK contains the original sidebar-label fix below, but not the complete extended semantics pass rebased onto beta 4.

## Confirmed issue and source fix

- Real-device semantic-tree inspection found four collapsed sidebar controls with no accessible name.
- Root cause: the visible sidebar text is rendered with zero alpha while collapsed, so it is absent from the exported accessibility node. The parent node exposed role and selection state but no name.
- `ModernSidebarBlurPanel.kt` now assigns the existing navigation label or profile name directly as the parent `contentDescription`. D-pad focus behavior is unchanged.

## Extended semantics pass (in working tree, not yet device-verified)

Additional missing roles and states were annotated across the app since the sidebar fix:

- `MainActivity.kt` — `CollapsedSidebarPill` exposes `Role.Button` and its label as `contentDescription`.
- `ModernSidebarBlurPanel.kt` — navigation items now merge into one node with `Role.Tab` plus `selected` state; profile items merge with `Role.Button` and the profile name.
- `AvatarPickerGrid.kt` — category tabs expose `Role.Tab` + `selected`; avatar cells expose `Role.Button` + `selected`.
- `ProfileBackgroundPicker.kt` — background cells expose `Role.Button` + `selected`.
- `ProfileEditorTabs.kt` — editor tabs expose `Role.Tab` + `selected`.
- `Synopsis.kt` — the "show full description" affordance exposes `Role.Button`.
- `PauseOverlay.kt` — cast detail back control is one `Role.Button` target; icon moved to `Icons.AutoMirrored.Filled.ArrowBack` with `contentDescription = null` so the merged node speaks once.
- `ProfileSelectionScreen.kt` — profile card, add-profile card, and overlay buttons expose `Role.Button`.
- `SettingsDesignSystem.kt` — toggle rows expose `Role.Switch` with `toggleableState`; action rows expose `Role.Button`.
- `PluginScreen.kt` — the stream-grouping, plugins-enabled, toggle-all, and scraper-enable rows expose `Role.Switch` + `toggleableState`; the add-repository, manage-from-phone, QR-close, confirm/reject, refresh, remove, test, and diagnostics rows expose `Role.Button`.
- `AddonManagerScreen.kt` — the manager entry cards (manage-from-phone, catalog order, collections, refresh) and the install, QR-close, confirm/reject, move-up/down, and remove buttons expose `Role.Button`; the add-on enable control exposes `Role.Switch` + `toggleableState`.
- Follow-up: the enable toggles in `ScraperCard` (`PluginScreen.kt`) and the add-on card (`AddonManagerScreen.kt`) carry role + state but no accessible name, because they have no in-container text label. Naming them needs a new string resource.

## On-device verification and real fix (2026-10-05)

- Built `fullDebug` (armeabi-v7a, signed with the local debug keystore) and installed on the `onn. 4K Plus` (API 34, `192.168.50.67:5555`); TalkBack stayed enabled.
- The rendered sidebar rail is `LegacySidebarButton` in `MainActivity.kt` (legacy drawer, `legacyCollapsedWidth` = 72dp), **not** `ModernSidebarBlurPanel`. Real-device hierarchy showed its four items exported with empty `content-desc` — i.e. still unnamed.
- Fix: `LegacySidebarButton` now marks its content `Box` with `semantics { contentDescription = label; role = Role.Tab; selected = selected }`. Its `ModernSidebarBlurPanel` counterpart was annotated the same way for the modern-sidebar variant.
- Verified on device (fresh rebuild + install): the rail exports `Home` (`selected=true`), `Search`, `Library`, `Settings`; D-pad Left moves focus into the sidebar and expands it; the label is not repeated (the title is drawn with no text semantics, so only the `contentDescription` node exists).
- Back on the Home root exited to the launcher (expected at a root route); deeper-screen Back focus restoration was not exercised.
- Not verifiable over adb: the spoken output and role announcements. `uiautomator` reports Compose nodes as `android.view.View` and does not expose roles, so `Role.Tab`/`Role.Switch`/`Role.Button` remain unconfirmed by ear.
- All three Home layouts audited on device (switched in Settings → Layout; app is not debuggable, so this was driven through the UI): **Modern** 17, **Grid** 11, **Classic** 10 clickable/focusable nodes, and **0** of them lacking an accessible name in any layout. The sidebar rail is shared across all three, so the `LegacySidebarButton` fix applies to every layout.
- Layout-specific rendering differs (`ModernHomeContent` vs `GridHomeContent` vs `ClassicHomeContent`), but no naming defect was found in any of them. Focus order / traps and role announcements were not machine-checkable.
- Swept more screens on device: Search, Library, Settings → Account, Settings → Content & Discovery, and Plugin management — all clean (0 unnamed focusable/clickable). Settings → Appearance had one defect: the theme swatch row's partially-clipped edge chip exported no accessible name while off-screen. Fixed in `ThemeSettingsScreen.kt`: `ThemeSwatchChip` now carries a single `clearAndSetSemantics { contentDescription = <theme name>; role = Role.RadioButton; selected = isSelected }` node. Device-verified — the swatches now export as `android.widget.RadioButton` with names, and the screen has 0 unnamed controls.
- Settings panels swept deterministically (TalkBack was disabled so injected D-pad keys drive Compose focus; re-enabled afterwards): Account, Profiles, Appearance, Layout, Content & Discovery, Playback, Integrations, Tracking, Advanced, About, Debug — all show 0 unnamed focusable/clickable controls after the fixes below.
- More fixes from the sweep: `SettingsRailButton` and `ThemeSwatchChip` now each expose a single `clearAndSetSemantics` node (name + role/state), which also stops partially-scrolled items from exporting unnamed. tv `Switch` double-targets were removed via `onCheckedChange = null` in `DebugToggleCard`, `CollectionEditorScreen` (pin-to-top / focus-glow / show-all-tab) and `CollectionEditorFolderContent` (play-gif / hide-title) so the clickable row is the single target; `CatalogOrderScreen`'s lone switch got a `contentDescription`.
- Residual naming gap: a row or chip clipped by a list edge can still export unnamed until it is scrolled into view (Compose prunes clipped semantics); confirmed to resolve on focus.
- Also swept and clean (0 unnamed): Add-ons manager, Catalog order (verified the switch `contentDescription`), Plugin management, Profile selection, the Profile Options dialog, and Edit Profile.
- Also swept and clean: Collections list (Import / New Collection).
- Not yet swept: Stream / Player (no streams resolved in this setup); the Collection editor sub-screens (left empty on purpose — creating a collection would mutate user data, and its switch double-targets were already fixed statically); and the remaining dialogs.

## Local-only build tweak (do not ship)

- `app/build.gradle.kts` and `ffmpeg-decoder-downmix/build.gradle.kts` pin `ndkVersion = "27.0.12077973"` (down from `29.0.14206865`) to match the NDK installed on this machine. Revert before opening a PR.

## Memory-constrained build recipe (6 GB machine)

- `:app:compileFullDebugKotlin` OOMs with the default daemon split (Kotlin daemon + Gradle daemon). What works:
  `gradlew :app:compileFullDebugKotlin -Dkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx3584m --max-workers=1`
  (stop stale Gradle daemons first: `gradlew --stop`).

## Verification performed

- 2026-10-07 (static naming sweep + stale test): `git diff --check` passed. `:app:compileFullDebugKotlin` passed with the memory-constrained recipe (in-process, `-Xmx3584m`, one worker, `--no-daemon`; warnings only, none in the changed lines). `:app:testFullDebugUnitTest` ran 1,863 tests: 1,857 passed, 5 failed, 1 skipped — the four known baseline failures plus the stale `StreamAutoPlaySelectorTest`; after correcting that test, its class re-ran green. No APK was assembled or installed, so the new semantics are compile-verified only.

- 2026-10-04 (plugin/addon semantics pass): `git diff --check` passed and `:app:compileFullDebugKotlin` passed with the memory-constrained recipe (5m 26s, warnings only, none in the changed lines). Semantics-only additions to `PluginScreen.kt` and `AddonManagerScreen.kt`; no layout, focus, or onClick logic changed. Not device-verified; not installed.
- 2026-10-04 (after upstream beta 4 fast-forward): `git diff --check` passed and `:app:compileFullDebugKotlin` passed with the memory-constrained recipe. `:app:testFullDebugUnitTest` ran 1,839 tests: 1,834 passed, 4 failed, 1 skipped. The failures were `LocalhostZeroCopyDataSourceTest.testHttpError404`, `DefaultAllocatorTest.testLateReleasedAllocationsMemoryLeak`, `TraktAuthServiceTest.refresh token 400 clears credentials and prevents another refresh`, and `DefaultDataSourceRoutingTest.testRoutingToLocalhostZeroCopyWhenEnabled`; none exercises the changed Compose semantics. No APK was assembled or installed.
- 2026-09-26 (extended semantics pass): `git diff --check` passed. `:app:compileFullDebugKotlin` passed with the memory-constrained recipe above. `:app:testFullDebugUnitTest` ran 1,263 tests: 1,248 passed, 14 failed, 1 skipped — identical to the pre-change baseline failures (unrelated media, networking, migration, and playback tests). No regression from the semantics annotations.
- 2026-09-24 (sidebar label fix, installed on device):
  - Verified Nuvio was the resumed package and input focus was inside its content grid.
  - Verified the semantic tree contained 22 focusable controls and four unlabeled sidebar controls before the fix.
  - `git diff --check` passed.
  - `:app:compileFullDebugKotlin` passed with one worker and a 1536 MB heap.
  - `:app:assembleFullDebug` passed with one worker, a 1536 MB heap, and temporary environment variables selecting the standard local Android debug keystore. No signing credentials or paths were written to source.
  - `:app:testFullDebugUnitTest` ran 1,263 tests: 1,248 passed, 14 failed in unrelated existing media, networking, migration, and playback tests, and 1 was skipped.
  - `:app:lintFullDebug` completed analysis but failed on the existing lint backlog: 3,165 errors, 1,771 warnings, and 25 hints. No lint finding points to the changed accessibility semantics lines.
  - `adb install -r` succeeded, preserving the installed package and its data.
  - Post-install package inspection confirmed version `1.0.0` (`1062`) and a new update time.
  - TalkBack and the existing launcher accessibility service remained enabled after installation.
  - Nuvio launched successfully and became both the focused app and current focused window.
  - Automated hierarchy inspection did not expose a focused semantic node, so spoken sidebar labels, D-pad traversal, and Back focus restoration still require direct user observation.

## Static naming sweep of the remaining surfaces (2026-10-07)

- Ran three parallel static audits over the surfaces the handover still listed as unswept — the player package, the collection-editor sub-screens, the settings screens, and the shared components/detail dialogs — looking for focusable or clickable Compose targets that would export a node with no usable name. Most candidates turned out to already be named (visible `Text` child, `Icon(contentDescription)`, `role`, or `clearAndSetSemantics`); the genuinely nameless ones below were fixed.
- `HeroCarousel.kt` — the carousel's `focusable()` container (the node that owns the left/right/enter key handling) had no name. It now carries `contentDescription` = the active slide's `name`.
- `Synopsis.kt` — the full-synopsis `focusable()` scroll surface had no name. It now carries `contentDescription` = the synopsis `title` (same pattern already used by `EpisodeOptionsOverlay`).
- `HeroSection.kt` — `PlayButton` renders an icon-only `Button` when `text == null` (the `nextToWatch == null` case), which was unnamed. It now falls back to `contentDescription = R.string.hero_play` only in that case, so the labelled case is unchanged.
- `MonochromePosterPlaceholder.kt` / `ContentCard.kt` / `GridContentCard.kt` — a card whose poster is missing shows `MonochromePosterPlaceholder` (and `ContentCard` its loading shimmer), none of which carried a name, so the clickable card exported unnamed in that state. `MonochromePosterPlaceholder` now accepts an optional `contentDescription` and both cards pass `item.name`; the shimmer box is named too. The labelled variant is unaffected because the label text lives outside the card.
- `CatalogRowSection.kt` and `collection/FolderDetailScreen.kt` — the decorative "loading more" skeleton `Card(onClick = {})` (already `canFocus = false`) exported an unnamed click action. Marked `clearAndSetSemantics {}` so it is removed from the accessibility tree.
- `settings/MDBListSettingsScreen.kt` — the rating-order reorder buttons used `Text("▲")` / `Text("▼")` glyphs as their only label. Each `Button` now carries `contentDescription = R.string.cd_move_up` / `cd_move_down` and the glyph `Text` is `clearAndSetSemantics {}` (so the button announces a real name instead of a triangle, and its click action is preserved).
- `player/PlayerOverlayScaffold.kt` — the full-screen background-dismiss scrim used `Modifier.clickable(...)`, whose `mergeDescendants` collapsed the entire overlay's text into one nameless button node. Replaced with a semantics-free `pointerInput { detectTapGestures { onDismiss() } }`, so tap-to-dismiss still works but the overlay no longer merges into a single node.

### Deliberately not changed

- The `focusable()` key-capture containers (`PlayerOverlayScaffold`'s key sink with `captureKeys = true`, and the `PlayerScreen` root container) are pure key-handling surfaces with no click action. Naming them would either be meaningless or merge the whole player into one node, so they were left as-is.
- The five settings text-field wrapper `Card(onClick = { field.requestFocus() })`s (Debrid, AnimeSkip, MDBList, PlaybackAutoPlay) still add an extra unnamed click stop beside the field. Removing the wrapper click changes touch/focus behaviour, so this was left for an intentional call rather than folded into a naming pass.

## Unrelated unit-test fix (2026-10-07)

- `:app:testFullDebugUnitTest` ran 1,863 tests: 1,857 passed, 5 failed, 1 skipped. Four failures are the known baseline media/networking/playback ones (`LocalhostZeroCopyDataSourceTest`, `DefaultAllocatorTest`, `TraktAuthServiceTest`, `FrameRateUtilsAfrTest`). The fifth, `StreamAutoPlaySelectorTest > bingeGroup-first respects source and addon plugin filters`, is a stale test: the selector was changed on 2026-10-06 (`d293973bd`, "ignore source filter") to search all streams for a binge-group match, but the test (last touched 2026-10-01) still asserted source/addon filtering. Updated the test to the documented behaviour (first binge-group match wins regardless of source filter) and renamed it to `bingeGroup-first ignores source and addon plugin filters`. No production code changed for this; it is a test-only correction.

## Next action

1. Done on device (2026-10-05): focus reaches the rail via D-pad, and each item exports a name and `selected` state.
2. Done on device (2026-10-05): no repeated label node on the rail; only the `contentDescription` node exists.
3. Still open: confirm by ear that the rail item is spoken as name + role + selected, and that Back restores content focus from a non-root screen (D-pad traversal / Back were not exercised deeper than Home).
4. Still open: spot-check by ear the modern-sidebar variant and the extended pass — settings toggles announce as switches, profile tabs/pickers announce role + selected, and the pause-overlay cast back control is a single button. `uiautomator` cannot confirm roles, so these need a listening pass with TalkBack.
5. Also still open from before: revert the local-only NDK pins and drop `hs_err_pid9004.log` before any commit/PR.
6. New (2026-10-07): the static naming-sweep fixes are compile- and unit-test-verified but not installed or device-verified. When next on device, spot-check by ear: the hero carousel announces the active title, the play button announces when icon-only, a card with no poster announces its title, and the MDBList rating-order buttons announce "Move up"/"Move down".


## 2026-10-11 — naming/state pass and the uiautomator artifact

- Added the missing `Role.Tab` / `Role.RadioButton` + `selected` state (and a few names) across player, library, collection, settings and the hero carousel; dropped nameless click actions on loading skeletons and the player overlay scrim. Committed as `daefdb1ea`, built and installed on the onn. 4K Plus (`com.nuviodebug.com` 1.1.0-beta.5).
- Decompiled `androidx.tv:tv-material` 1.1.0: `Card(onClick)` -> `Surface` -> `tvClickable(modifier, ...)` = `modifier.focusable(handleDPadEnter(...)).semantics(mergeDescendants = true) { onClick; onLongClick }`, applied to the same `Box` that holds the card content. So cards and the sidebar rail are already named from their merged content.
- Therefore the "unnamed clickable + named child" split `uiautomator` shows for the rail and row cards is a dump artifact, not a defect — consistent with this document's earlier on-device "0 unnamed" result. Do not "fix" it by adding content descriptions to those cards.
- Roles / `selected` still cannot be confirmed through `uiautomator` (Compose exposes generic views). An ear pass with TalkBack remains the only real check.

## 2026-10-12 â€” CI-first dev process, further a11y fixes, open items

Commits on `dev` (pushed to origin/thebetterfarhan):
- daefdb1ea naming/state pass (roles + selected across player, library, collection, settings, hero)
- cd0497e27 stale StreamAutoPlaySelector test fix
- a032b24e6 profile overlays: scrims/panel blockers no longer merge; profile row + terms link roles; PIN field name
- 3c1249728 seek bar progressBarRangeInfo; PIN overlay root unmerged
- b2f7aada3 liveRegion = Polite on auth/QR status, PIN support/error line, player error + report status
- f9025c896, d71fa7cbb CI workflows

CI (fork-local; separate files so `git merge upstream/dev` never conflicts):
- .github/workflows/dev-unit-tests.yml â€” full unit suite on push to dev and PRs into dev. Quarantines three known-baseline failures via .github/ci/quarantine-tests.init.gradle.kts (excludes by pattern; no test source weakened).
- .github/workflows/dev-debug-apk.yml â€” assembles fullDebug and uploads app-full-armeabi-v7a-debug.apk. Signs with the DEBUG_KEYSTORE_BASE64 repo secret (the local ~/.android/debug.keystore), so `adb install -r` updates in place and keeps data.

Local dev loop:
- gradle.properties: -Xmx3g, daemon, caching, vfs.watch=false, kotlin.incremental=true.
- app/build.gradle.kts: ABI split gated behind -Pnuvio.localAbi (local-only edit).
- dev-install.ps1 = local one-ABI build (slow on this box); dev-fetch-apk.ps1 = download the CI APK and install (fast). Both kept out of git via .git/info/exclude.
- PowerShell: CurrentUser execution policy set to RemoteSigned so the .ps1 helpers run.

Measured on this machine: of a 33-min build, 18 min is a single Kotlin compile, and it is memory-bound (0.29 GB free RAM -> paging). That is the hardware, not the flags; CI builds the same APK in ~12 min.

Resolved without a change: InputField/NuvioTextField wrap a BasicTextField in a clickable Surface. tv-material3 applies semantics(mergeDescendants = true) on the same node (decompiled tv-material 1.1.0), so the field's semantics merge up and stay editable. Changing it would risk focus/IME for no proven gain.

Deliberately not changed: LoadingOverlay's decorative cd_loading_backdrop/cd_loading_logo strings (intentional); liveRegion beyond auth/PIN/player (44 error-colour sites â€” needs a deliberate design call, not a blanket apply).

Open: an ear pass for roles/selected/wording on the TV; Compose semantics tests (Robolectric, JVM, would run in CI) for role/state regression cover; upstream PR for the a11y commits + the stale test.

