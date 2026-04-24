// Mirrors ProfileSettingsSyncService.kt (app/src/main/java/com/omnio/tv/core/sync/).
// The TV stores every DataStore Preferences value as a type-tagged JSON object:
//   { type: "string"|"boolean"|"int"|"long"|"float"|"double"|"string_set", value: ... }
// Whole-blob pushes wrap features in:
//   { version: 1, features: { <feature_name>: { <pref_key>: <encoded>, ... }, ... } }
// The web panel must preserve this envelope exactly — a silent drift here corrupts settings.

export type EnvelopeType =
  | "string"
  | "boolean"
  | "int"
  | "long"
  | "float"
  | "double"
  | "string_set";

export type EncodedValue =
  | { type: "string"; value: string }
  | { type: "boolean"; value: boolean }
  | { type: "int"; value: number }
  | { type: "long"; value: number }
  | { type: "float"; value: number }
  | { type: "double"; value: number }
  | { type: "string_set"; value: string[] };

export type DecodedValue = string | boolean | number | string[];

export type EncodedFeature = Record<string, EncodedValue>;
export type EncodedFeatures = Record<string, EncodedFeature>;

export interface SettingsBlob {
  version: number;
  features: EncodedFeatures;
}

export function decode(encoded: EncodedValue): DecodedValue {
  switch (encoded.type) {
    case "string":
    case "boolean":
    case "int":
    case "long":
    case "float":
    case "double":
      return encoded.value;
    case "string_set":
      return [...encoded.value];
  }
}

export function decodeFeature(feature: EncodedFeature): Record<string, DecodedValue> {
  const out: Record<string, DecodedValue> = {};
  for (const [key, encoded] of Object.entries(feature)) {
    out[key] = decode(encoded);
  }
  return out;
}

// Type-preserving encoders. Callers must pick the right one — the TV's decode
// path uses `intPreferencesKey` vs `longPreferencesKey` vs `floatPreferencesKey`
// vs `doublePreferencesKey` based on `type`, so encoding an int as "long" will
// make the TV-side read silently return null and fall back to the default.
export const encodeString = (value: string): EncodedValue => ({ type: "string", value });
export const encodeBoolean = (value: boolean): EncodedValue => ({ type: "boolean", value });
export const encodeInt = (value: number): EncodedValue => ({ type: "int", value: value | 0 });
export const encodeLong = (value: number): EncodedValue => ({ type: "long", value: Math.trunc(value) });
export const encodeFloat = (value: number): EncodedValue => ({ type: "float", value });
export const encodeDouble = (value: number): EncodedValue => ({ type: "double", value });

// Matches ProfileSettingsSyncService.kt:316-324 — the TV sorts the string set
// alphabetically on every encode, so the web side must do the same to avoid
// signature churn that triggers spurious pushes.
export const encodeStringSet = (value: Iterable<string>): EncodedValue => ({
  type: "string_set",
  value: Array.from(new Set(value)).sort(),
});

export class EnvelopeDecodeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EnvelopeDecodeError";
  }
}

// Validates the shape of a raw JSON blob pulled from profile_settings.settings_json.
// Raises on structural violations; does NOT validate feature key sets (that's Zod's job).
export function parseBlob(raw: unknown): SettingsBlob {
  if (raw == null || typeof raw !== "object") {
    throw new EnvelopeDecodeError("blob is not an object");
  }
  const obj = raw as Record<string, unknown>;
  const version = obj.version;
  if (typeof version !== "number") {
    throw new EnvelopeDecodeError("blob.version is not a number");
  }
  const features = obj.features;
  if (features == null || typeof features !== "object") {
    throw new EnvelopeDecodeError("blob.features is not an object");
  }
  const parsedFeatures: EncodedFeatures = {};
  for (const [featureKey, featureValue] of Object.entries(features as Record<string, unknown>)) {
    if (featureValue == null || typeof featureValue !== "object") {
      throw new EnvelopeDecodeError(`features.${featureKey} is not an object`);
    }
    const parsedFeature: EncodedFeature = {};
    for (const [prefKey, rawEncoded] of Object.entries(featureValue as Record<string, unknown>)) {
      parsedFeature[prefKey] = parseEncoded(rawEncoded, `features.${featureKey}.${prefKey}`);
    }
    parsedFeatures[featureKey] = parsedFeature;
  }
  return { version, features: parsedFeatures };
}

function parseEncoded(raw: unknown, path: string): EncodedValue {
  if (raw == null || typeof raw !== "object") {
    throw new EnvelopeDecodeError(`${path} is not an object`);
  }
  const obj = raw as { type?: unknown; value?: unknown };
  const type = obj.type;
  const value = obj.value;
  switch (type) {
    case "string":
      if (typeof value !== "string") throw new EnvelopeDecodeError(`${path} expected string value`);
      return { type: "string", value };
    case "boolean":
      if (typeof value !== "boolean") throw new EnvelopeDecodeError(`${path} expected boolean value`);
      return { type: "boolean", value };
    case "int":
    case "long":
    case "float":
    case "double":
      if (typeof value !== "number") throw new EnvelopeDecodeError(`${path} expected number value`);
      return { type, value };
    case "string_set":
      if (!Array.isArray(value) || !value.every((v) => typeof v === "string")) {
        throw new EnvelopeDecodeError(`${path} expected string[] value`);
      }
      return { type: "string_set", value: [...value] };
    default:
      throw new EnvelopeDecodeError(`${path} has unknown envelope type: ${String(type)}`);
  }
}
