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
