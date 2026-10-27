# Native Usenet sorting profiles

Choose **Settings → Usenet → Search results → Sorting profile**. Existing installations start
on **Current sorting**, preserving their previous sort order. Selecting a profile does not replace
providers, indexers, search limits or the saved custom profile.

These profiles apply only to results from Nuvio's **built-in NNTP providers and Newznab indexers**.
Addon results (including addon-supplied NZBs), debrid sources, plugins and media-server streams keep
their existing order. There is no global stream sorting hook.

| Profile | Quality order and preferences |
| --- | --- |
| Balanced | 4K remux, 4K Blu-ray encode, 1080p remux, 4K WEB-DL, 4K WEBRip, 1080p Blu-ray, 1080p WEB-DL, 1080p WEBRip, then other results. Movies up to 30 GiB and episodes up to 5 GiB are preferred before the quality tiers. Three results per resolution/source-quality category lead; extras follow. |
| Best Quality | The same quality tiers, without a preferred size ceiling. Source-specific release-group scores break ties before language and descending size. Four results per category lead. |
| Smaller Files | Prefer 1080p WEB-DL, WEBRip and Blu-ray encodes, then 4K WEB releases and 720p, with remux alternatives below. Movies up to 12 GiB and episodes up to 2 GiB are preferred. HEVC/AV1 get a small score boost within a quality tier. Two results per category lead. |
| 1080p / SDR | Prefer 720p–1080p releases without HDR/Dolby Vision labels. AVC and AAC/DDP receive small boosts. These preferences precede quality tiers, with the same size preferences as Smaller Files. HDR, 4K and unknown formats remain as alternatives. Absence of an HDR label is not proof of SDR or playback compatibility. |

The TV and phone both offer an optional **Preferred language** picker for these four presets.
Tagged matches lead before quality and size; other languages and unlabelled releases remain eligible.
The preference survives preset changes. **No language preference** restores the preset's standard order.
**Current sorting** ignores this preference and **Custom profile** uses its own languages and sorting
priorities. Choosing either preserves the quick preference for the next time a built-in preset is used.

These sizes are preferences: larger releases remain selectable. Existing minimum-resolution,
maximum-size, maximum-age, password and low-quality search filters remain authoritative. A season
pack uses its per-episode size when every included season's episode count is known. Without those
counts its ranking size is unknown; the whole pack is not mistaken for one oversized episode.

The presets deprioritize a small set of heavily compressed release groups and labelled upscales.
When at least five other eligible results remain, they omit those matches. With fewer alternatives,
they retain them below stronger matches within their quality tier. This avoids erasing scarce results.

Unknown groups and missing metadata are retained unless a user explicitly requires/excludes them.
Language, codec, HDR and audio facts come from release-name tags, not a probe of the actual media.
A DV-only filename tag does not prove a device can or cannot play the file. Profiles do not impose
a device capability filter automatically.

## Phone editor

Select **Edit sorting from phone**, scan the QR code and open **Sorting**. The link is scoped to
the TV profile that opened it. Closing the overlay stops the server; changing TV profile makes the
old session unable to read or save settings.

- Pick a preset, optionally choose a language and **Save**, or open **Customize** to create a custom profile.
  Preview, custom rules and import/export start collapsed to keep the initial view short.
- Set language preferences with lowercase codes, for example `en, ja`. These are preferences,
  not required languages. Results without language tags remain eligible.
- Reorder quality tiers; their first match wins. Empty resolution/quality lists match any value.
- Reorder sorting priorities. A score normally applies within a quality tier; moving **Rule score**
  above **Quality tier** allows it to outweigh quality tiers.
- Rule rows start collapsed and Save stays visible while editing. Score rules accumulate. Release-group boosts in the supplied presets are qualified by their
  source quality, so a WEB group does not automatically receive a remux endorsement.
- Rules can match all or any of up to eight conditions. Fields include release name, release group,
  source quality, resolution, codec, visual/audio tags, language, indexer name, size, age and
  movie/series context. `Is one of` and `Is not one of` take comma-separated values.
- **Exclude** removes matches. **Require** keeps matches. Set **Minimum remaining results** above
  zero to skip a filter when it would leave too few eligible candidates; zero makes the filter strict.
  Known failed releases do not count towards that minimum.
- **Top results per category** adds variety near the top. Overflow follows, rather than being
  removed, so it remains available for playback fallback within the existing global result limit.
- Preview accepts one line per sample: `release name | size GiB | age days | indexer`. Optional
  numbers may be omitted. Choose movies or episodes. The native TV engine returns the ordering,
  scores, matched scoring rules and exclusions. Preview neither searches indexers nor saves.
  The TV's existing search filters still apply to real searches; the preview evaluates the profile.
- Export/import uses a native `nuvio.stream-ranking` JSON envelope. Exports contain only the profile,
  never source credentials. Import validates and normalizes a draft before displaying it; **Save**
  is still required. Preset changes preserve the last saved custom profile.

Edits are explicit drafts. There are no template downloads, expression scripts, remote rule syncs
or AIOStreams template compatibility.

## Behaviour and cost

Release facts are parsed lazily once per release. Language parsing is deferred until a profile uses
language preferences or conditions; detected languages are reused across incremental searches.
Up to eight compiled profile snapshots are cached.
Custom regular expressions use RE2/J, which does not support lookaround or backreferences and avoids
backtracking explosions. Profiles allow at most 80 rules, eight conditions per rule and 256 characters
per regex. The authenticated phone endpoints cap profile requests at 64 KiB and previews at 50 samples.

Profiles evaluate in the existing search worker, before the global result limit. Known playback
failures stay below other candidates even when a profile awards them a high score or varies the
categories. Existing successful-playback ordering remains controlled by **Available releases first**.
Final tie breakers use release name and identity, making order independent of indexer arrival order.
Profiles never fetch NZBs, probe articles/media or request additional metadata to rank a result.

## Ranking references

Nuvio's presets and structured rules are original implementations. The use of quality tiers,
source-specific release-group rankings and conditional result retention was informed by
[Tam-Taro's setup](https://github.com/Tam-Taro/SEL-Filtering-and-Sorting) and
[AIOStreams scored sorting](https://docs.aiostreams.viren070.me/guides/scored-sorting/).

The group memberships were reviewed against the official
[TRaSH Guides Radarr custom formats](https://github.com/TRaSH-Guides/Guides/tree/master/docs/json/radarr/cf)
(remux tiers 1–3, WEB tiers 1–3, leading HD/UHD Blu-ray groups and selected LQ groups), October 2026. The numeric boosts
are Nuvio's own small, source-specific preferences. They are editable snapshots, not quality
certifications or a promise that an individual release will play.
