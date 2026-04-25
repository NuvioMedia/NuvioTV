import { describe, expect, it } from "vitest";
import {
  decode,
  decodeFeature,
  encodeBoolean,
  encodeDouble,
  encodeFloat,
  encodeInt,
  encodeLong,
  encodeString,
  encodeStringSet,
  EnvelopeDecodeError,
  parseBlob,
  type EncodedFeature,
  type SettingsBlob,
} from "./envelope";
import { encodeFeature } from "./schemas";

describe("envelope encode/decode", () => {
  it("round-trips each primitive type", () => {
    expect(decode(encodeString("hello"))).toBe("hello");
    expect(decode(encodeBoolean(true))).toBe(true);
    expect(decode(encodeBoolean(false))).toBe(false);
    expect(decode(encodeInt(42))).toBe(42);
    expect(decode(encodeLong(1_234_567_890))).toBe(1_234_567_890);
    expect(decode(encodeFloat(3.5))).toBe(3.5);
    expect(decode(encodeDouble(2.71828))).toBe(2.71828);
  });

  it("sorts and dedupes string_set to match Kotlin encode", () => {
    const encoded = encodeStringSet(["banana", "apple", "banana", "cherry"]);
    expect(encoded).toEqual({ type: "string_set", value: ["apple", "banana", "cherry"] });
  });

  it("truncates int/long instead of rounding", () => {
    expect(encodeInt(3.9)).toEqual({ type: "int", value: 3 });
    expect(encodeLong(3.9)).toEqual({ type: "long", value: 3 });
    expect(encodeLong(-3.9)).toEqual({ type: "long", value: -3 });
  });
});

describe("parseBlob", () => {
  it("accepts a valid blob", () => {
    const raw = {
      version: 1,
      features: {
        theme_settings: {
          selected_theme: { type: "string", value: "DARK" },
          selected_font: { type: "string", value: "INTER" },
        },
      },
    };
    const parsed = parseBlob(raw);
    expect(parsed.version).toBe(1);
    expect(parsed.features.theme_settings.selected_theme).toEqual({
      type: "string",
      value: "DARK",
    });
  });

  it("rejects an unknown envelope type", () => {
    const raw = {
      version: 1,
      features: { theme_settings: { x: { type: "blob", value: "nope" } } },
    };
    expect(() => parseBlob(raw)).toThrow(EnvelopeDecodeError);
  });

  it("rejects type/value mismatch", () => {
    const raw = {
      version: 1,
      features: { theme_settings: { x: { type: "boolean", value: "true" } } },
    };
    expect(() => parseBlob(raw)).toThrow(/expected boolean value/);
  });

  it("rejects a missing features object", () => {
    expect(() => parseBlob({ version: 1 })).toThrow(/features/);
  });
});

describe("decodeFeature", () => {
  it("decodes every key in a feature", () => {
    const decoded = decodeFeature({
      tmdb_enabled: { type: "boolean", value: true },
      tmdb_language: { type: "string", value: "en" },
      tmdb_use_episodes: { type: "boolean", value: false },
    });
    expect(decoded).toEqual({ tmdb_enabled: true, tmdb_language: "en", tmdb_use_episodes: false });
  });
});

describe("encodeFeature", () => {
  it("encodes a tmdb feature and matches TV-side shape", () => {
    const encoded = encodeFeature("tmdb_settings", {
      tmdb_enabled: true,
      tmdb_language: "de",
    });
    expect(encoded).toEqual({
      tmdb_enabled: { type: "boolean", value: true },
      tmdb_language: { type: "string", value: "de" },
    });
  });

  it("hard-fails on an unknown key (schema drift detector)", () => {
    expect(() => encodeFeature("tmdb_settings", { bogus_key: true })).toThrow(
      /no encoder for tmdb_settings.bogus_key/
    );
  });

  it("drops undefined values without throwing", () => {
    const encoded = encodeFeature("tmdb_settings", {
      tmdb_enabled: true,
      tmdb_language: undefined,
    });
    expect(encoded).toEqual({ tmdb_enabled: { type: "boolean", value: true } });
  });

  it("encodes trakt string_set alphabetically", () => {
    const encoded = encodeFeature("trakt_settings", {
      dismissed_next_up_keys: ["zulu", "alpha", "mike"],
    });
    expect(encoded.dismissed_next_up_keys).toEqual({
      type: "string_set",
      value: ["alpha", "mike", "zulu"],
    });
  });
});

describe("full blob round-trip", () => {
  // Simulates a real export from the TV. Each feature contains at least one key
  // for each envelope type its DataStore.kt uses.
  const sampleBlob: SettingsBlob = {
    version: 1,
    features: {
      theme_settings: {
        selected_theme: { type: "string", value: "BLACK" },
        selected_font: { type: "string", value: "ROBOTO" },
      },
      trailer_settings: {
        trailer_enabled: { type: "boolean", value: true },
        trailer_delay_seconds: { type: "int", value: 5 },
      },
      player_settings: {
        player_preference: { type: "string", value: "INTERNAL" },
        decoder_priority: { type: "int", value: 1 },
        next_episode_threshold_percent_v2: { type: "float", value: 99 },
        stream_auto_play_selected_addons: {
          type: "string_set",
          value: ["alpha", "bravo"],
        },
      },
      tmdb_settings: {
        tmdb_enabled: { type: "boolean", value: true },
        tmdb_language: { type: "string", value: "en" },
      },
      mdblist_settings: {
        mdblist_enabled: { type: "boolean", value: false },
        mdblist_api_key: { type: "string", value: "" },
      },
      animeskip_settings: {
        animeskip_enabled: { type: "boolean", value: true },
        animeskip_client_id: { type: "string", value: "abc123" },
      },
      trakt_settings: {
        continue_watching_days_cap: { type: "int", value: 60 },
        show_unaired_next_up: { type: "boolean", value: true },
        dismissed_next_up_keys: { type: "string_set", value: ["key1", "key2"] },
      },
      emby_credentials: {
        emby_server_url: { type: "string", value: "https://emby.example.com" },
        emby_api_key: { type: "string", value: "secret" },
        emby_user_id: { type: "string", value: "user-uuid" },
      },
      layout_settings: {
        selected_layout: { type: "string", value: "MODERN" },
        hero_section_enabled: { type: "boolean", value: true },
        poster_card_width_dp: { type: "int", value: 126 },
      },
    },
  };

  it("parseBlob is the inverse of structural stringify", () => {
    const parsed = parseBlob(sampleBlob);
    expect(parsed).toEqual(sampleBlob);
  });

  it("every feature round-trips through encodeFeature/decodeFeature without loss", () => {
    for (const [featureKey, feature] of Object.entries(sampleBlob.features)) {
      if (featureKey === "track_preference") continue;
      const decoded = decodeFeature(feature);
      const reEncoded = encodeFeature(featureKey as Parameters<typeof encodeFeature>[0], decoded);
      expect(reEncoded).toEqual(feature);
    }
  });
});

describe("partial-merge simulation (mirrors migration 008's jsonb_set)", () => {
  // Shape-equivalent of what sync_push_profile_settings_partial does on the server:
  // jsonb_set(blob, '{features,<key>}', encoded_feature) — replaces the named
  // feature subtree, leaves every other feature untouched.
  function simulatePartialMerge(
    existing: SettingsBlob,
    featureKey: string,
    newFeature: Record<string, { type: string; value: unknown }>
  ): SettingsBlob {
    return {
      ...existing,
      features: {
        ...existing.features,
        [featureKey]: newFeature as never,
      },
    };
  }

  it("tmdb form save: encode → merge → parse → decode preserves user edits and unrelated features", () => {
    const existing: SettingsBlob = {
      version: 1,
      features: {
        // Pretend TV pushed both tmdb and theme; user edits tmdb only.
        tmdb_settings: {
          tmdb_enabled: { type: "boolean", value: false },
          tmdb_language: { type: "string", value: "en" },
        },
        theme_settings: {
          selected_theme: { type: "string", value: "DARK" },
          selected_font: { type: "string", value: "INTER" },
        },
      },
    };

    const userEdit = {
      tmdb_enabled: true,
      tmdb_language: "de",
      tmdb_use_artwork: true,
      tmdb_use_episodes: false,
      tmdb_use_more_like_this: true,
    };

    const encoded = encodeFeature("tmdb_settings", userEdit);
    const merged = simulatePartialMerge(existing, "tmdb_settings", encoded);

    // Theme must survive untouched — that's the whole point of partial merge.
    expect(merged.features.theme_settings).toEqual(existing.features.theme_settings);

    // The merged blob must round-trip through parseBlob without loss.
    const reparsed = parseBlob(merged);
    expect(reparsed).toEqual(merged);

    // Decoding the tmdb feature reproduces the exact user edit.
    expect(decodeFeature(reparsed.features.tmdb_settings)).toEqual(userEdit);
  });

  it("envelope types match what TmdbSettingsDataStore.kt expects", () => {
    const encoded = encodeFeature("tmdb_settings", {
      tmdb_enabled: true,
      tmdb_language: "fr",
      tmdb_use_artwork: false,
    });
    // booleanPreferencesKey on the TV side requires type === "boolean";
    // stringPreferencesKey requires type === "string". A mismatch silently
    // makes the TV-side read fall back to the default — no error surfaces.
    expect(encoded.tmdb_enabled.type).toBe("boolean");
    expect(encoded.tmdb_language.type).toBe("string");
    expect(encoded.tmdb_use_artwork.type).toBe("boolean");
  });

  it("mdblist form save: encode → merge → parse → decode preserves user edits and unrelated features", () => {
    const existing: SettingsBlob = {
      version: 1,
      features: {
        mdblist_settings: {
          mdblist_enabled: { type: "boolean", value: false },
          mdblist_api_key: { type: "string", value: "" },
        },
        tmdb_settings: {
          tmdb_enabled: { type: "boolean", value: true },
          tmdb_language: { type: "string", value: "en" },
        },
      },
    };

    const userEdit = {
      mdblist_enabled: true,
      mdblist_api_key: "abc-secret-123",
      mdblist_show_imdb: true,
      mdblist_show_trakt: false,
      mdblist_show_metacritic: true,
    };

    const encoded = encodeFeature("mdblist_settings", userEdit);
    const merged = simulatePartialMerge(existing, "mdblist_settings", encoded);

    // tmdb subtree must survive untouched.
    expect(merged.features.tmdb_settings).toEqual(existing.features.tmdb_settings);

    const reparsed = parseBlob(merged);
    expect(decodeFeature(reparsed.features.mdblist_settings)).toEqual(userEdit);

    // api_key is a stringPreferencesKey on the TV side — confirm it.
    expect(encoded.mdblist_api_key.type).toBe("string");
  });

  // --- the remaining 7 v2 blob domains -----------------------------------
  // Each test:
  //   1. Starts with an existing blob containing the target feature plus an
  //      unrelated feature that MUST survive the partial-merge.
  //   2. Encodes a representative user edit via encodeFeature.
  //   3. Simulates the migration-008 jsonb_set merge.
  //   4. Asserts the unrelated feature is byte-equal afterwards.
  //   5. Asserts encode → parse → decode round-trips the user edit cleanly.
  //   6. Asserts at least one envelope-type guard so a future schema-drift
  //      that re-categorises a key (e.g. int → long) fails loudly here.

  // Pick a decoy feature different from the one under test so the partial-merge
  // can prove it left an unrelated subtree byte-equal.
  function decoyFor(featureKey: string): { key: string; value: EncodedFeature } {
    if (featureKey === "tmdb_settings") {
      return {
        key: "theme_settings",
        value: { selected_theme: { type: "string", value: "DARK" } },
      };
    }
    return {
      key: "tmdb_settings",
      value: {
        tmdb_enabled: { type: "boolean", value: true },
        tmdb_language: { type: "string", value: "en" },
      },
    };
  }

  function partialMergeRoundTrip(
    featureKey: Parameters<typeof encodeFeature>[0],
    userEdit: Record<string, unknown>,
    expectedTypes: Record<string, string>
  ) {
    const decoy = decoyFor(featureKey);
    const existing: SettingsBlob = {
      version: 1,
      features: {
        [featureKey]: {},
        [decoy.key]: decoy.value,
      },
    };
    const encoded = encodeFeature(featureKey, userEdit);
    const merged = simulatePartialMerge(existing, featureKey, encoded);
    // The decoy feature must survive the partial-merge byte-equal.
    expect(merged.features[decoy.key]).toEqual(decoy.value);
    const reparsed = parseBlob(merged);
    expect(decodeFeature(reparsed.features[featureKey] as EncodedFeature)).toEqual(
      userEdit
    );
    for (const [key, expectedType] of Object.entries(expectedTypes)) {
      expect(encoded[key]?.type, `${featureKey}.${key}`).toBe(expectedType);
    }
  }

  it("animeskip form save round-trips and types match", () => {
    partialMergeRoundTrip(
      "animeskip_settings",
      { animeskip_enabled: true, animeskip_client_id: "client-xyz" },
      { animeskip_enabled: "boolean", animeskip_client_id: "string" }
    );
  });

  it("emby form save round-trips and types match", () => {
    partialMergeRoundTrip(
      "emby_credentials",
      {
        emby_server_url: "https://emby.example.com",
        emby_api_key: "secret",
        emby_user_id: "user-uuid-123",
      },
      {
        emby_server_url: "string",
        emby_api_key: "string",
        emby_user_id: "string",
      }
    );
  });

  it("trailer form save round-trips with int delay", () => {
    // trailer_delay_seconds is intPreferencesKey — encoding as long would
    // make the TV-side read return null and fall back to the default of 7.
    partialMergeRoundTrip(
      "trailer_settings",
      { trailer_enabled: true, trailer_delay_seconds: 12 },
      { trailer_enabled: "boolean", trailer_delay_seconds: "int" }
    );
  });

  it("theme form save round-trips and uses string envelope (not enum)", () => {
    // Both keys store the Kotlin enum's name() as a string. The encoder must
    // emit type === "string", not some custom "enum" tag.
    partialMergeRoundTrip(
      "theme_settings",
      { selected_theme: "OCEAN", selected_font: "DM_SANS" },
      { selected_theme: "string", selected_font: "string" }
    );
  });

  it("trakt form save round-trips, dismissed_next_up_keys is a sorted string_set", () => {
    const encoded = encodeFeature("trakt_settings", {
      continue_watching_days_cap: 90,
      show_unaired_next_up: true,
      show_meta_comments: false,
      watch_progress_source: "TRAKT",
      dismissed_next_up_keys: ["zulu|s1e1", "alpha|s1e1"],
    });
    expect(encoded.continue_watching_days_cap.type).toBe("int");
    expect(encoded.show_unaired_next_up.type).toBe("boolean");
    expect(encoded.watch_progress_source.type).toBe("string");
    expect(encoded.dismissed_next_up_keys).toEqual({
      type: "string_set",
      value: ["alpha|s1e1", "zulu|s1e1"],
    });

    partialMergeRoundTrip(
      "trakt_settings",
      {
        continue_watching_days_cap: 30,
        show_unaired_next_up: false,
        show_meta_comments: true,
        watch_progress_source: "NUVIO_SYNC",
        dismissed_next_up_keys: ["a", "b"],
      },
      {
        continue_watching_days_cap: "int",
        show_unaired_next_up: "boolean",
        watch_progress_source: "string",
        dismissed_next_up_keys: "string_set",
      }
    );
  });

  it("player form save: legacy threshold is int, _v2 threshold is float", () => {
    // The TV reads next_episode_threshold_percent_v2 with floatPreferencesKey
    // and the legacy next_episode_threshold_percent with intPreferencesKey.
    // If either type is wrong the TV silently uses the default — this test
    // pins both contracts.
    const userEdit = {
      player_preference: "INTERNAL",
      internal_player_engine: "EXOPLAYER",
      decoder_priority: 2,
      preferred_audio_language: "eng",
      next_episode_threshold_mode: "PERCENTAGE",
      next_episode_threshold_percent: 95,
      next_episode_threshold_percent_v2: 99.5,
      next_episode_threshold_minutes_before_end: 3,
      next_episode_threshold_minutes_before_end_v2: 2.5,
      // Set already alphabetised — decode round-trips sets in sorted order
      // (encoder sorts to match Kotlin's Set<String>.toSortedSet() write).
      stream_auto_play_selected_addons: ["addonA", "addonB"],
      min_buffer_ms: 30_000,
    };
    const encoded = encodeFeature("player_settings", userEdit);
    expect(encoded.next_episode_threshold_percent.type).toBe("int");
    expect(encoded.next_episode_threshold_percent_v2.type).toBe("float");
    expect(encoded.next_episode_threshold_minutes_before_end.type).toBe("int");
    expect(encoded.next_episode_threshold_minutes_before_end_v2.type).toBe("float");
    expect(encoded.decoder_priority.type).toBe("int");
    expect(encoded.min_buffer_ms.type).toBe("int");
    // string_set must come out alphabetised even if user toggled in a different order.
    expect(encoded.stream_auto_play_selected_addons).toEqual({
      type: "string_set",
      value: ["addonA", "addonB"],
    });

    partialMergeRoundTrip("player_settings", userEdit, {
      next_episode_threshold_percent_v2: "float",
      next_episode_threshold_percent: "int",
    });
  });

  it("layout form save: poster dimensions are int, raw catalog json stays string", () => {
    partialMergeRoundTrip(
      "layout_settings",
      {
        selected_layout: "MODERN",
        hero_section_enabled: true,
        poster_card_width_dp: 140,
        poster_card_height_dp: 200,
        focused_poster_backdrop_trailer_playback_target: "HERO_MEDIA",
        // home_catalog_order_keys ships a Gson-serialised JSON list as a single
        // string — must round-trip as a single string, not get parsed.
        home_catalog_order_keys: '["row.cinemeta.movie","row.cinemeta.series"]',
      },
      {
        selected_layout: "string",
        poster_card_width_dp: "int",
        poster_card_height_dp: "int",
        home_catalog_order_keys: "string",
      }
    );
  });
});
