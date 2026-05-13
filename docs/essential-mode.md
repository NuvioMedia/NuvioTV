# NuvioTV Essential Mode Documentation

## Purpose

Essential mode is a streamlined NuvioTV experience for users who want to install add-ons, find something, and play it without navigating the full customization surface.

Advanced mode remains the existing power-user experience. It keeps the complete settings rail, layout controls, integrations, plug-ins, diagnostics, and detailed playback tuning.

The goal is not to remove capabilities from the app. The goal is to put the minimum playback path first, keep the UI calm for new or casual users, and keep every advanced feature reachable through an intentional mode switch.

## Existing App Reference

This spec is based on the current app structure in:

- `app/src/main/java/com/nuvio/tv/MainActivity.kt`
- `app/src/main/java/com/nuvio/tv/ui/navigation/Screen.kt`
- `app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/SettingsScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/addon/AddonManagerScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/plugin/PluginScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/LayoutSettingsScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt`
- `app/src/main/java/com/nuvio/tv/data/local/*DataStore.kt`

Current first launch flow:

1. `MainActivity` shows the Nuvio account QR sign-in flow if the first-launch auth prompt has not been completed.
2. If multiple profiles or a PIN are present, `ProfileSelectionScreen` is shown.
3. If `LayoutPreferenceDataStore.hasChosenLayout` is false, `LayoutSelectionScreen` is shown.
4. Main app shell loads with root routes for Home, Search, Library, Add-ons, and Settings.

Current root navigation:

- Home
- Search
- Library
- Add-ons
- Settings

Current settings rail:

- Account
- Profiles
- Appearance
- Layout
- Plugins
- Integration
- Playback
- Trakt
- About
- Advanced
- Debug in debug builds

Current playback path:

1. User installs at least one Stremio-compatible add-on.
2. Home, Search, or Discover loads catalogs and metadata from installed add-ons.
3. Detail page starts stream resolution.
4. Stream screen lists available streams or auto-selects a stream depending on playback settings.
5. Player screen handles playback, audio, subtitles, source switching, episodes, skip intro, next episode, speed, aspect ratio, stream info, and engine switching.

## Product Definition

### Essential Mode

Essential mode should answer one user need:

> I just want NuvioTV to work with the least setup possible.

Essential mode keeps:

- Add-on installation and basic add-on management.
- Home, Search, Library, Details, Stream selection, and Player.
- A small settings surface for app mode, account sign-in, language, basic playback, basic subtitles, and basic appearance.
- Safe recovery paths such as switching to Advanced mode, removing broken add-ons, and changing player preference.

Essential mode hides:

- Layout customization beyond one or two safe choices.
- Catalog ordering and collection editing.
- Plug-in repository management.
- Integration tuning for TMDB, MDBList, Anime-Skip, and Trakt advanced options.
- Detailed playback tuning, decoder settings, frame-rate switching, subtitle renderer controls, P2P stats, cache tools, and debug tools.

### Advanced Mode

Advanced mode is the app as it exists today, plus the mode switch. It should expose all current screens and controls.

Advanced mode is for users who want:

- Full layout and home customization.
- Catalog order and collection management.
- Plug-in repositories and scraper management.
- Trakt, TMDB, MDBList, and Anime-Skip configuration.
- Playback engine selection, frame-rate matching, subtitle rendering, P2P settings, and diagnostics.

### Non-Goals

- Essential mode is not a parental-control mode.
- Essential mode is not a content-filtering mode.
- Essential mode should not uninstall add-ons, delete libraries, reset user preferences, or disable existing advanced settings without explicit confirmation.
- Essential mode should not fork the app into a separate build flavor.

## Recommended Mode Behavior

### New Users

For a fresh install, show a mode choice before the current layout selection step:

- Essential: recommended, simple setup, default for most TV users.
- Advanced: full customization and all settings.

If the user chooses Essential:

1. Mark the mode as Essential.
2. Set the home layout to Modern.
3. Mark layout selection as completed.
4. Continue to Add-on Setup if no add-ons are installed.

If the user chooses Advanced:

1. Mark the mode as Advanced.
2. Continue to the existing `LayoutSelectionScreen`.
3. Show the full app experience after layout selection.

### Existing Users

Existing users should default to Advanced when no mode value exists and `hasChosenLayout` is already true.

This avoids hiding settings for users who already use the current app.

### Returning Essential Users

Essential users should land directly in the main app shell after account/profile gates. They should not see the layout selection screen again.

If they have no installed add-ons, Home and Search empty states should deep-link to Add-ons.

## Essential Onboarding Flow

Recommended first-run flow:

1. Account sign-in
   - Keep the existing QR sign-in flow.
   - Allow continue without account.
   - Explain that account sync is optional.

2. Experience mode
   - Show Essential and Advanced choices.
   - Essential is selected by default.
   - Advanced mentions full customization.

3. Add-on setup
   - If no add-ons are installed, show a focused Add-on Setup screen.
   - Primary action: manage from phone with QR.
   - Secondary action: enter add-on URL manually.
   - Success state: show installed add-on and continue to Home.

4. Basic preferences
   - App language.
   - Preferred subtitle language.
   - Optional player preference only if external player apps are detected or the user asks.

5. Home
   - Use Modern layout.
   - Keep Discover enabled.
   - Keep default poster sizes.

## Essential Navigation

Keep the root drawer simple:

- Home
- Search
- Library
- Add-ons
- Settings

Do not add a separate "Setup" root item. Setup should be represented inside Add-ons and Settings so the root navigation stays stable.

### Home

Essential behavior:

- Keep Modern home.
- Keep Continue Watching.
- Keep catalog rows.
- Keep collections visible if they already exist.
- Hide controls for choosing hero catalogs, poster dimensions, focus animation, catalog labels, and layout style.

Advanced behavior:

- Existing Home behavior and all layout settings remain available.

### Search

Essential behavior:

- Keep text search.
- Keep voice search.
- Keep Discover if enabled.
- Hide Discover enable/disable setting from Essential settings.

Advanced behavior:

- Existing Search and Discover behavior.
- Discover enable/disable remains in Layout settings.

### Library

Essential behavior:

- Keep Library visible.
- Keep basic local and account-backed library browsing.
- Keep filters and sorting because they are part of finding content, not app customization.
- Hide advanced Trakt library source settings unless Trakt is connected and the user switches to Advanced.

Advanced behavior:

- Existing Library behavior.

### Add-ons

Essential behavior:

- Show manual install URL.
- Show Manage from phone QR.
- Show installed add-ons.
- Allow removing broken or unwanted add-ons.
- Hide add-on reordering buttons.
- Hide Catalog Order entry.
- Hide Collections entry unless collections already exist.
- In the phone web UI, use an Essential web config mode that manages add-on URLs only.

Advanced behavior:

- Existing Add-on Manager behavior.
- Catalog Order and Collections are available.
- Add-on reorder controls are available.
- Existing phone web config remains full.

### Settings

Essential settings should be a simplified settings surface, not the current full rail.

Recommended Essential settings sections:

1. Experience
   - Mode: Essential or Advanced.
   - "Switch to Advanced" action.

2. Add-ons
   - Open Add-ons.
   - Manage from phone.
   - Installed add-on count.

3. Playback basics
   - Player: Internal, External, Ask every time.
   - Stream selection: Manual or Auto-play first available.
   - Auto-play next episode.
   - P2P streaming toggle or status if torrent streams are encountered.

4. Subtitles and audio
   - Preferred subtitle language.
   - Subtitle size.
   - Preferred audio language.

5. Appearance
   - App language.
   - Theme.
   - Font only if the UI can show it without creating a long customization section.

6. Account and profiles
   - Sign in or sign out.
   - Sync overview if signed in.
   - Manage profiles only for primary profile users.

7. About
   - App version and support/contributors link.

Advanced settings should continue to use the existing `SettingsScreen` rail.

## Feature Visibility Matrix

| Area | Essential | Advanced | Notes |
| --- | --- | --- | --- |
| Home route | Show | Show | Essential uses Modern defaults and hides layout customization. |
| Search route | Show | Show | Search is core playback discovery. |
| Discover | Show | Show | Essential hides the setting that disables Discover. |
| Library route | Show | Show | Library is core user workflow. |
| Add-ons route | Show simplified | Show full | Essential keeps install, QR, installed list, remove. |
| Manual add-on URL install | Show | Show | Required for playback setup. |
| Manage add-ons from phone | Show | Show | Best TV setup path. |
| Add-on reorder | Hide | Show | Advanced organization feature. |
| Catalog Order | Hide | Show | Advanced home tuning. |
| Collections | Hide by default | Show | Essential may show existing collections but should not surface the editor. |
| Plug-ins | Hide | Show when build supports it | Plug-ins are advanced source providers. |
| Account QR sign-in | Show | Show | Optional sync and profiles. |
| Profile selection | Show when required | Show | Required if multiple profiles or PIN. |
| Profile management | Limited | Full | Essential can manage profiles from primary profile only. |
| Appearance: theme | Show | Show | Minimal customization. |
| Appearance: language | Show | Show | Accessibility and localization. |
| Appearance: font | Optional | Show | Keep if it does not clutter Essential. |
| AMOLED options | Hide | Show | Advanced visual preference. |
| Layout selection | Skip after Essential choice | Show | Essential sets Modern. |
| Layout settings | Hide | Show | Advanced customization. |
| Playback player preference | Show | Show | Recovery path if internal playback fails. |
| Internal player engine | Hide | Show | Advanced playback tuning. |
| Auto-switch player engine on error | Hide | Show | Advanced recovery. |
| Loading and pause overlays | Hide | Show | Keep defaults. |
| OSD clock | Hide | Show | Advanced player preference. |
| Skip intro setting | Hide | Show | Keep default enabled. |
| Frame-rate and resolution matching | Hide | Show | Device-specific advanced setting. |
| Stream auto-play mode | Show simplified | Show full | Essential uses simple Manual/Auto toggle. |
| Auto-play source scope, regex, add-on/plugin allowlists | Hide | Show | Advanced stream selection. |
| Next episode auto-play | Show | Show | Common playback behavior. |
| Reuse last link cache | Hide | Show | Advanced stream behavior. |
| Audio preferred language | Show | Show | Useful basic setup. |
| Secondary audio language | Hide | Show | Advanced preference. |
| Decoder priority | Hide | Show | Advanced codec tuning. |
| Skip silence | Hide | Show | Advanced playback behavior. |
| Tunneling | Hide | Show | Device-specific advanced setting. |
| Dolby Vision profile fallback | Hide | Show | Device-specific advanced setting. |
| MPV hardware decode mode | Hide | Show | Engine-specific advanced setting. |
| Subtitle preferred language | Show | Show | Useful basic setup. |
| Secondary subtitle language | Hide | Show | Advanced preference. |
| Subtitle startup mode | Hide | Show | Advanced add-on subtitle behavior. |
| Subtitle size | Show | Show | Accessibility. |
| Subtitle vertical offset | Hide | Show | Advanced style. |
| Subtitle colors, outline, bold | Hide | Show | Advanced style. |
| libass and render type | Hide | Show | Advanced renderer behavior. |
| P2P enable consent | Show when needed | Show | Essential must not surprise users with torrents. |
| P2P stats visibility | Hide | Show | Advanced diagnostics. |
| TMDB settings | Hide | Show | Keep defaults in Essential. |
| MDBList settings | Hide | Show | Advanced ratings integration. |
| Anime-Skip settings | Hide | Show | Advanced integration. |
| Trakt connection | Show as optional account integration or hide under Advanced | Show full | For Essential, prefer Nuvio account sync first. |
| Trakt library/progress/comment settings | Hide | Show | Advanced integration. |
| Advanced performance settings | Hide | Show | Advanced only. |
| Network speed test | Hide | Show | Diagnostics. |
| Clear Continue Watching cache | Hide | Show | Diagnostics/recovery. |
| Debug settings | Hide | Show in debug builds | Developer-only. |
| Player play/pause/seek | Show | Show | Core playback. |
| Player audio/subtitle selectors | Show | Show | Core playback recovery. |
| Player source switching | Show | Show | Core playback recovery. |
| Player episode panel | Show | Show | Core series navigation. |
| Player speed/aspect/external/stream info | Hide behind More or hide | Show | Advanced player actions. |
| Player engine switch | Hide | Show | Advanced playback recovery. |

## Recommended Essential Defaults

Use existing defaults where possible to avoid surprises.

Recommended defaults for a new Essential user:

| Setting | Value | Reason |
| --- | --- | --- |
| Experience mode | Essential | Streamlined first-run. |
| Home layout | Modern | Best current TV-first experience. |
| Layout chosen | true | Skips layout selection. |
| Search Discover | true | Helps users find content. |
| Player preference | Internal | Fewer external app dependencies. |
| Internal engine | ExoPlayer | Current default. |
| Stream auto-play mode | Manual | Avoid wrong source selection by default. |
| Auto-play next episode | false initially, visible as a simple toggle | Current default and user-controlled. |
| Preferred audio language | Device | Current default. |
| Preferred subtitle language | English unless device locale maps cleanly | Current default is English. |
| Add-on subtitle startup mode | All subtitles | Current default. |
| Skip intro | enabled | Current default and helpful. |
| Loading overlay | enabled | Current default. |
| Pause overlay | enabled | Current default. |
| OSD clock | enabled | Current default. |
| Trailer autoplay | current default | Hide setting unless Advanced. |
| P2P | disabled until user consents | Current default and safer. |
| Plug-ins | no repositories installed | Hide management in Essential. |

## Detailed Essential Settings Design

### Experience Section

Rows:

- Experience mode
  - Value: Essential
  - Action: switch to Advanced

- Advanced features
  - Value: Hidden
  - Action: explain that all settings are available in Advanced

Mode switching rules:

- Switching from Essential to Advanced immediately reveals the full settings rail.
- Switching from Advanced to Essential hides advanced controls but does not reset values.
- If active advanced features are enabled, show a short note such as "Some advanced settings are still active."

### Add-ons Section

Rows:

- Manage add-ons
  - Opens `AddonManagerScreen`.

- Manage from phone
  - Starts QR mode from `AddonManagerViewModel.startQrMode()`.

- Installed add-ons
  - Shows count and first few add-on names.

Essential add-on screen changes:

- Hide `CatalogOrderEntryCard`.
- Hide `CollectionsEntryCard` unless the user already has collections.
- Hide move up/down buttons in `AddonCard`.
- Keep remove action.
- Keep read-only notices for secondary profiles using primary add-ons.

### Playback Basics Section

Rows:

- Player
  - Maps to `PlayerPreference`.
  - Options: Internal, External, Ask every time.

- Stream selection
  - Essential label: Manual selection or Auto-play first result.
  - Maps to `StreamAutoPlayMode.MANUAL` and `StreamAutoPlayMode.FIRST_STREAM`.
  - Hide regex mode.

- Auto-play next episode
  - Maps to `streamAutoPlayNextEpisodeEnabled`.

- P2P streaming
  - Maps to `TorrentSettings.p2pEnabled`.
  - The first torrent playback should still show consent even if the settings row is not visited.

Hide in Essential:

- Internal player engine.
- Auto-switch internal engine.
- Loading status toggle.
- Reuse last link.
- Source scope.
- Add-on and plug-in allowlists.
- Regex pattern.
- Threshold mode and threshold sliders.
- Stream auto-play timeout.

### Subtitles and Audio Section

Rows:

- Preferred subtitle language.
- Subtitle size.
- Preferred audio language.

Optional rows:

- Disable subtitles by default.
- Subtitle secondary language only if the user has already configured it.

Hide in Essential:

- Subtitle secondary language by default.
- Subtitle startup mode.
- Subtitle vertical offset.
- Subtitle colors.
- Subtitle bold.
- Subtitle outline controls.
- libass.
- libass render type.
- Secondary audio language.
- Decoder priority.
- Skip silence.
- Tunneling.
- Dolby Vision fallback.
- MPV hardware decode mode.

### Appearance Section

Rows:

- App language.
- Theme.
- Font if desired.

Hide in Essential:

- AMOLED mode.
- AMOLED surfaces mode.
- Full layout settings.
- Sidebar settings.
- Poster card style.
- Home content toggles.
- Focused poster behavior.
- Detail page visual tuning.

### Account and Profiles Section

Rows:

- Sign in with QR if signed out.
- Signed-in email and sign out if signed in.
- Sync overview if signed in.
- Manage profiles if active profile is primary.

Keep current restrictions:

- Secondary profiles using primary add-ons should remain read-only in add-on management.
- Secondary profiles using primary plug-ins should not get plug-in management in Essential.

### About Section

Rows:

- App version.
- Supporters/contributors.
- Legal/disclaimer link if present in About.

## Advanced Mode Surface

Advanced mode should keep the existing settings rail and current behavior:

- Account
- Profiles
- Appearance
- Layout
- Plugins
- Integration
- Playback
- Trakt
- About
- Advanced
- Debug for debug builds

The only new Advanced-mode requirement is that the mode switch remains visible, ideally at the top of Settings or as the first rail item.

## Implementation Plan

### 1. Add Experience Mode Model

Create a small model:

```kotlin
enum class ExperienceMode {
    ESSENTIAL,
    ADVANCED
}
```

Recommended package:

- `app/src/main/java/com/nuvio/tv/domain/model/ExperienceMode.kt`

### 2. Persist Experience Mode

Add a DataStore for mode.

Recommended approach:

- Use profile-scoped storage through `ProfileDataStoreFactory` if mode should follow each profile.
- Use app-scoped storage if mode should be device-wide.

Best fit for NuvioTV:

- Use app-scoped storage for first MVP, because experience mode changes the device UI complexity rather than media data.
- Keep a future option to sync mode per profile.

Suggested file:

- `app/src/main/java/com/nuvio/tv/data/local/ExperienceModeDataStore.kt`

Migration behavior:

- If no stored mode exists and `LayoutPreferenceDataStore.hasChosenLayout` is false, treat the install as new and show mode selection.
- If no stored mode exists and `hasChosenLayout` is true, default to Advanced.
- If stored mode exists, always use it.

### 3. Add Central Policy

Avoid scattering mode checks across many screens.

Suggested file:

- `app/src/main/java/com/nuvio/tv/core/experience/ExperienceModePolicy.kt`

Policy should answer questions like:

- Is full settings rail visible?
- Are plug-ins visible?
- Is catalog ordering visible?
- Are collections management controls visible?
- Which playback settings sections are visible?
- Which player controls are visible?

Example shape:

```kotlin
data class ExperienceVisibility(
    val showFullSettings: Boolean,
    val showPluginManagement: Boolean,
    val showCatalogOrder: Boolean,
    val showCollectionEditor: Boolean,
    val showAdvancedPlaybackSettings: Boolean,
    val showAdvancedPlayerControls: Boolean
)
```

### 4. Update MainActivity First Launch

Current logic:

- Account QR gate.
- Profile selection gate.
- Layout selection gate.
- Main shell.

New logic:

1. Load experience mode state.
2. If mode is unknown and this is a new install, show `ExperienceModeSelectionScreen`.
3. If Essential is selected, call `layoutPreferenceDataStore.setLayout(HomeLayout.MODERN)` so `hasChosenLayout` becomes true.
4. If Advanced is selected, keep current `LayoutSelectionScreen`.

Add screen:

- `app/src/main/java/com/nuvio/tv/ui/screens/ExperienceModeSelectionScreen.kt`

### 5. Update Navigation

Add route:

- `Screen.ExperienceModeSelection`

Keep root drawer items stable in both modes:

- Home
- Search
- Library
- Add-ons
- Settings

Do not remove root routes from `NuvioNavHost`; mode should control screen content, not break navigation.

### 6. Add Essential Settings Screen

Options:

1. Add `EssentialSettingsContent` and branch inside `SettingsScreen`.
2. Add `EssentialSettingsScreen` and route to it when mode is Essential.

Recommended:

- Branch inside `SettingsScreen` so the Settings root route remains stable.
- Reuse existing rows from `SettingsDesignSystem.kt`.
- Keep the full current rail for Advanced.

Files affected:

- `SettingsScreen.kt`
- `SettingsDesignSystem.kt` only if new reusable row types are needed.
- `PlaybackSettingsScreen.kt` or new focused components for basic playback rows.

### 7. Filter Add-on Management

Update `AddonManagerScreen` to accept an experience-mode visibility policy.

Essential changes:

- Hide catalog order card.
- Hide collections card unless collections already exist and the user needs read-only access.
- Hide move up/down buttons in `AddonCard`.
- Keep remove action.
- Start QR server in an add-ons-only mode.

Current code uses:

- `AddonManagementAccess.webConfigMode(profile)`
- `AddonWebConfigMode.FULL`
- `AddonWebConfigMode.COLLECTIONS_ONLY`

Add:

- `AddonWebConfigMode.ADDONS_ONLY`

Use ADDONS_ONLY when:

- mode is Essential and profile is not read-only.

### 8. Filter Plug-ins

Essential should hide plug-in management from Settings and any direct plug-in route entry.

Important existing-user rule:

- Do not disable existing plug-in repositories or scrapers when switching to Essential.
- If plug-ins are enabled and have active scrapers, show a small "Advanced providers active" note in Essential Add-ons or Playback basics with a "Switch to Advanced" action.

### 9. Filter Playback Settings

Current playback settings are split across:

- `PlaybackSettingsScreen.kt`
- `PlaybackSettingsSections.kt`
- `PlaybackAutoPlaySettings.kt`
- `PlaybackAudioSettings.kt`
- `PlaybackSubtitleSettings.kt`

Recommended implementation:

- Add a simplified `EssentialPlaybackSettingsContent`.
- Reuse existing dialogs for player preference, languages, and subtitle language.
- Do not try to make every existing section conditionally hide individual rows for MVP.

Essential rows:

- Player preference.
- Stream selection manual/first stream.
- Auto-play next episode.
- P2P enabled.
- Preferred audio language.
- Preferred subtitle language.
- Subtitle size.

### 10. Filter Player Controls

Current player controls include:

- Play/pause.
- Next episode.
- Subtitles.
- Audio.
- Sources.
- Player engine switch.
- Episodes.
- More actions for speed, aspect ratio, external player, stream info.

Essential should show:

- Play/pause.
- Next episode when available.
- Subtitles.
- Audio.
- Sources.
- Episodes for series.

Essential should hide:

- Player engine switch.
- Speed.
- Aspect ratio.
- Open in external player from More.
- Stream info.
- Torrent stats toggle.

Keep keyboard shortcuts and back behavior stable.

### 11. Keep Detail and Stream Screens Mostly Intact

Detail and Stream are part of finding and playing content, not customization.

Essential should keep:

- Play.
- Play manually if auto-play is enabled.
- Episode list.
- Basic metadata.
- Cast and related content if already displayed.
- Stream list and source selection.

Potential Essential simplification:

- Hide source chips and filters on Stream screen by default.
- Keep them if there are many add-ons or if the user opens an "All sources" control.

Do not change MVP unless the stream screen feels too busy after Essential settings are hidden.

## UX Copy

Mode selection:

- Essential
  - "Simple setup for watching. Install add-ons, search, and play."

- Advanced
  - "Full control over layout, integrations, playback, plug-ins, and diagnostics."

Essential Settings mode row:

- Title: "Experience mode"
- Value: "Essential"
- Subtitle: "Advanced settings are hidden until you switch modes."

No add-ons empty state:

- Title: "Add add-ons to start watching"
- Subtitle: "Install from your phone with a QR code or enter an add-on URL."
- Primary action: "Manage from phone"
- Secondary action: "Enter URL"

Advanced provider notice:

- Title: "Advanced providers are active"
- Subtitle: "Some plug-in sources are still enabled. Switch to Advanced to manage them."

## Data Safety Rules

Switching modes must never:

- Delete add-ons.
- Delete plug-in repositories.
- Delete library items.
- Clear watch progress.
- Reset theme, layout, playback, subtitle, or account settings.
- Change P2P state without explicit user action.
- Change Trakt or account sync source without explicit user action.

Switching to Essential only changes visibility.

The only exception is first-run Essential setup, where selecting Essential can set layout to Modern and mark layout selection completed because no prior layout preference exists.

## Build Flavor Rules

Full flavor:

- Advanced can show plug-ins.
- Advanced can show in-app updates.
- Advanced can show in-app trailer playback settings.

Play Store flavor:

- Plug-ins stay hidden because `AppFeaturePolicy.pluginsEnabled` is false.
- In-app updates stay hidden because `AppFeaturePolicy.inAppUpdatesEnabled` is false.
- In-app trailer playback settings follow `AppFeaturePolicy.inAppTrailerPlaybackEnabled`.

Essential mode should respect `AppFeaturePolicy` in both flavors.

## Testing Checklist

Fresh install, Essential:

- User can choose Essential.
- Layout selection is skipped.
- Modern home is selected.
- If no add-ons are installed, Home/Search guide the user to Add-ons.
- User can install add-on by URL.
- User can install/manage add-ons from phone QR.
- Installed add-on catalogs appear on Home.
- User can search, open detail, select stream, and play.
- Settings shows only Essential sections.
- Switch to Advanced reveals the full current settings rail.

Fresh install, Advanced:

- User can choose Advanced.
- Existing layout selection appears.
- Full settings rail appears.
- Add-ons, plug-ins, catalog order, collections, integrations, playback tuning, advanced, and debug behave as before.

Existing install:

- Existing user with no stored mode defaults to Advanced.
- Existing layout and settings remain unchanged.
- User can switch to Essential without losing advanced values.
- User can switch back to Advanced and see previous values.

Profiles:

- Primary profile can manage add-ons and profiles.
- Secondary profile using primary add-ons sees read-only add-on notice.
- Secondary profile using primary plug-ins does not see plug-in management in Essential.
- PIN profile selection still works before the main shell.

Playback:

- Essential player still supports play/pause, seek, subtitles, audio, sources, and episodes.
- Hidden advanced controls do not leave focus traps.
- External player preference still works.
- Torrent stream consent still appears before enabling P2P.

Build variants:

- Play Store build does not show plug-ins in Essential or Advanced.
- Full build shows plug-ins only in Advanced.
- In-app trailer settings follow feature policy.

## Suggested Implementation Order

1. Add mode model, DataStore, and migration behavior.
2. Add mode selection screen.
3. Wire first-run flow in `MainActivity`.
4. Add central visibility policy.
5. Add Essential settings content.
6. Gate Add-on Manager advanced controls.
7. Gate plug-in visibility.
8. Add Essential playback settings content.
9. Gate advanced player controls.
10. Add tests for mode defaults and visibility.
11. Do manual TV/D-pad QA.

## Test Targets To Add

Recommended unit tests:

- `ExperienceModeDataStoreTest`
  - default unknown state
  - set Essential
  - set Advanced

- `ExperienceModeMigrationTest`
  - new install defaults to mode selection
  - existing install defaults to Advanced

- `ExperienceModePolicyTest`
  - Essential hides advanced settings
  - Advanced shows current settings
  - feature policy still respected

- `AddonManagementAccessTest`
  - Essential primary profile uses add-ons-only web config
  - Essential read-only profile uses collections-only or read-only behavior
  - Advanced preserves full web config

Recommended Compose/UI tests where practical:

- Settings visible section count in Essential.
- Settings full rail in Advanced.
- Add-on manager hides catalog order in Essential.
- Player controls hide engine switch and More actions in Essential.

## MVP Acceptance Criteria

Essential mode is ready when:

- A fresh user can go from install to playback setup without seeing layout customization.
- A user without add-ons gets a clear add-on setup path.
- Essential settings contain only mode, add-ons, basic playback, subtitle/audio basics, appearance basics, account/profile basics, and about.
- Advanced mode still behaves like the current app.
- Switching modes does not delete or reset user data.
- D-pad focus remains predictable in all visible Essential screens.

## Future Enhancements

Possible later improvements:

- Guided add-on health check after installation.
- "Test playback" sample flow after add-on setup.
- Essential add-on recommendations screen if the project later supports a curated legal catalog.
- Profile-level mode syncing.
- Per-profile Essential mode for children or guests.
- A compact "Setup status" card showing account, add-ons, subtitles, and playback readiness.

