import { Smartphone } from "lucide-react";
import { createServerSupabase } from "@/lib/supabase/server";

interface LinkedDevice {
  id: string;
  device_user_id: string;
  device_name: string | null;
  linked_at: string;
}

export default async function DevicesPage() {
  const supabase = await createServerSupabase();
  const { data, error } = await supabase
    .from("linked_devices")
    .select("id, device_user_id, device_name, linked_at")
    .order("linked_at", { ascending: false });
  if (error) throw error;
  const devices = (data ?? []) as LinkedDevice[];

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Linked devices</h1>
        <p className="text-sm text-slate-400">
          TVs that paired with this account via QR or sync code.
        </p>
      </header>

      {devices.length === 0 ? (
        <div className="space-y-3">
          <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-slate-300">
            No QR-paired devices.
          </div>
          <div className="rounded-2xl border border-slate-700/30 bg-slate-800/20 p-4 text-xs text-slate-400">
            TVs signed in directly with email and password share this account but do not appear
            here — they don&apos;t go through the device-pairing flow. This list only shows TVs
            that were paired by scanning a QR code or entering a 6-digit sync code.
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {devices.map((d) => (
            <div
              key={d.id}
              className="flex items-center gap-4 rounded-2xl border border-slate-700/50 bg-slate-800/40 p-4"
            >
              <Smartphone className="h-6 w-6 text-slate-400" />
              <div className="min-w-0 flex-1">
                <div className="font-medium text-slate-100">
                  {d.device_name || "Unnamed device"}
                </div>
                <div className="text-xs text-slate-500">
                  linked {new Date(d.linked_at).toLocaleString()}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="rounded-2xl border border-slate-700/30 bg-slate-800/20 p-4 text-xs text-slate-400">
        Revoking a device requires server-side confirmation and is part of v2. To unlink for now,
        sign the TV out from its own Settings → Account screen.
      </div>
    </div>
  );
}
