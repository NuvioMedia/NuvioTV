"use client";

// Generic schema-driven field renderer used by Player and Layout settings forms.
// Each form passes a list of FieldSpec and a values object; FieldRow renders
// the appropriate input and updates via onChange. The encoder map in
// lib/settings/schemas.ts handles type-correct envelope encoding on save.

export type FieldKind = "boolean" | "int" | "float" | "string" | "stringSet" | "select";

export interface FieldSpec<K extends string = string> {
  key: K;
  label: string;
  kind: FieldKind;
  options?: string[];
  hint?: string;
  min?: number;
  max?: number;
  step?: number;
}

export function FieldRow({
  spec,
  value,
  onChange,
}: {
  spec: FieldSpec;
  value: unknown;
  onChange: (v: unknown) => void;
}) {
  if (spec.kind === "boolean") {
    return (
      <label className="flex cursor-pointer items-center justify-between gap-3 rounded-lg border border-transparent p-2 hover:border-slate-700/60 hover:bg-slate-900/30">
        <span className="min-w-0 flex-1">
          <span className="block text-sm text-slate-100">{spec.label}</span>
          {spec.hint && (
            <span className="mt-0.5 block text-xs text-slate-500">{spec.hint}</span>
          )}
        </span>
        <input
          type="checkbox"
          checked={Boolean(value)}
          onChange={(e) => onChange(e.target.checked)}
          className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
        />
      </label>
    );
  }
  if (spec.kind === "int" || spec.kind === "float") {
    const step = spec.step ?? (spec.kind === "int" ? 1 : "any");
    return (
      <label className="block">
        <span className="mb-0.5 block text-sm text-slate-100">{spec.label}</span>
        {spec.hint && (
          <span className="mb-1 block text-xs text-slate-500">{spec.hint}</span>
        )}
        <input
          type="number"
          min={spec.min}
          max={spec.max}
          step={step}
          value={value === undefined ? "" : String(value)}
          onChange={(e) => {
            const s = e.target.value;
            if (s === "") {
              onChange(undefined);
              return;
            }
            const n =
              spec.kind === "int" ? Number.parseInt(s, 10) : Number.parseFloat(s);
            if (Number.isNaN(n)) return;
            onChange(n);
          }}
          className="w-32 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-1.5 text-sm text-slate-100 outline-none focus:border-primary"
        />
      </label>
    );
  }
  if (spec.kind === "select") {
    const opts = spec.options ?? [];
    const cur = typeof value === "string" ? value : "";
    return (
      <label className="block">
        <span className="mb-0.5 block text-sm text-slate-100">{spec.label}</span>
        {spec.hint && (
          <span className="mb-1 block text-xs text-slate-500">{spec.hint}</span>
        )}
        <select
          value={cur}
          onChange={(e) => onChange(e.target.value)}
          className="rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-1.5 text-sm text-slate-100 outline-none focus:border-primary"
        >
          <option value="">—</option>
          {opts.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </select>
      </label>
    );
  }
  if (spec.kind === "stringSet") {
    const cur = Array.isArray(value) ? (value as string[]).join("\n") : "";
    return (
      <label className="block">
        <span className="mb-0.5 block text-sm text-slate-100">{spec.label}</span>
        {spec.hint && (
          <span className="mb-1 block text-xs text-slate-500">{spec.hint}</span>
        )}
        <textarea
          rows={3}
          value={cur}
          onChange={(e) => {
            const lines = e.target.value
              .split(/\r?\n/)
              .map((s) => s.trim())
              .filter((s) => s.length > 0);
            onChange(lines);
          }}
          className="w-full rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-1.5 font-mono text-xs text-slate-100 outline-none focus:border-primary"
        />
      </label>
    );
  }
  return (
    <label className="block">
      <span className="mb-0.5 block text-sm text-slate-100">{spec.label}</span>
      {spec.hint && (
        <span className="mb-1 block text-xs text-slate-500">{spec.hint}</span>
      )}
      <input
        type="text"
        value={typeof value === "string" ? value : ""}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-1.5 text-sm text-slate-100 outline-none focus:border-primary"
      />
    </label>
  );
}
