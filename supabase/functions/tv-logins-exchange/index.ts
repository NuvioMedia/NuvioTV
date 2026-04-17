import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.8";
import { corsHeaders } from "../_shared/cors.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

type TvLoginExchangeRequest = {
  code?: string;
  device_nonce?: string;
};

const jsonHeaders = {
  ...corsHeaders,
  "Content-Type": "application/json",
};

function jsonResponse(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers: jsonHeaders });
}

async function sha256Hex(value: string) {
  const encoded = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", encoded);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response(null, { headers: corsHeaders });
  }

  if (request.method !== "POST") {
    return jsonResponse(405, { error: "Method not allowed" });
  }

  if (!SUPABASE_URL || !SUPABASE_ANON_KEY || !SUPABASE_SERVICE_ROLE_KEY) {
    return jsonResponse(500, { error: "Supabase Edge Function environment is incomplete" });
  }

  const payload = await request.json().catch(() => null) as TvLoginExchangeRequest | null;
  const code = payload?.code?.trim().toUpperCase();
  const deviceNonce = payload?.device_nonce?.trim();

  if (!code || !deviceNonce) {
    return jsonResponse(400, { error: "Missing code or device_nonce" });
  }

  if (deviceNonce.length < 16) {
    return jsonResponse(400, { error: "Invalid device nonce" });
  }

  const adminClient = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
    },
  });

  const nonceHash = await sha256Hex(deviceNonce);

  const { data: sessionRow, error: sessionError } = await adminClient
    .from("tv_login_sessions")
    .select("id, status, approved_by_user_id, device_nonce_hash, expires_at")
    .eq("code", code)
    .single();

  if (sessionError || !sessionRow) {
    return jsonResponse(404, { error: "Invalid TV login code" });
  }

  if (sessionRow.device_nonce_hash !== nonceHash) {
    return jsonResponse(403, { error: "Invalid device nonce" });
  }

  if (new Date(sessionRow.expires_at).getTime() <= Date.now()) {
    await adminClient
      .from("tv_login_sessions")
      .update({ status: "expired" })
      .eq("id", sessionRow.id);
    return jsonResponse(410, { error: "TV login expired" });
  }

  if (sessionRow.status === "used") {
    return jsonResponse(409, { error: "TV login already used" });
  }

  if (sessionRow.status !== "approved") {
    return jsonResponse(409, { error: "TV login not approved" });
  }

  if (!sessionRow.approved_by_user_id) {
    return jsonResponse(500, { error: "Approved TV login session is missing a user" });
  }

  const { data: userData, error: userError } = await adminClient.auth.admin.getUserById(sessionRow.approved_by_user_id);
  if (userError || !userData.user?.email) {
    return jsonResponse(500, { error: "Could not load approved user" });
  }

  const { data: linkData, error: linkError } = await adminClient.auth.admin.generateLink({
    type: "magiclink",
    email: userData.user.email,
  });

  const tokenHash = linkData?.properties?.hashed_token;
  if (linkError || !tokenHash) {
    return jsonResponse(500, { error: "Could not mint TV login session" });
  }

  const { data: otpData, error: otpError } = await adminClient.auth.verifyOtp({
    token_hash: tokenHash,
    type: "magiclink",
  });

  if (otpError || !otpData.session?.access_token || !otpData.session.refresh_token) {
    return jsonResponse(500, { error: "Could not exchange TV login session" });
  }

  const { error: updateError } = await adminClient
    .from("tv_login_sessions")
    .update({
      status: "used",
      exchanged_at: new Date().toISOString(),
    })
    .eq("id", sessionRow.id)
    .eq("status", "approved");

  if (updateError) {
    return jsonResponse(500, { error: "Could not finalize TV login session" });
  }

  return jsonResponse(200, {
    access_token: otpData.session.access_token,
    refresh_token: otpData.session.refresh_token,
    token_type: otpData.session.token_type ?? "bearer",
    expires_in: otpData.session.expires_in,
  });
});
