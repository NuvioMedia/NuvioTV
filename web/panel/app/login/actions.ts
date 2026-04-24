"use server";

import { redirect } from "next/navigation";
import { createServerSupabase } from "@/lib/supabase/server";

function friendly(message: string): string {
  const m = message.toLowerCase();
  if (m.includes("invalid login credentials")) return "Incorrect email or password.";
  if (m.includes("email not confirmed")) return "Please confirm your email first.";
  if (m.includes("user already registered"))
    return "An account with this email already exists. Try signing in instead.";
  if (m.includes("invalid email")) return "Please enter a valid email address.";
  if (m.includes("password") && m.includes("short")) return "Password is too short.";
  if (m.includes("rate limit")) return "Too many attempts. Please try again later.";
  return message;
}

export async function signIn(
  formData: FormData
): Promise<{ error?: string; info?: string }> {
  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "");
  const next = String(formData.get("next") ?? "/profiles");

  if (!email || !password) return { error: "Enter your email and password." };

  const supabase = await createServerSupabase();
  const { error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) return { error: friendly(error.message) };

  redirect(next);
}

export async function signUp(formData: FormData): Promise<{ error?: string; info?: string }> {
  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "");

  if (!email || !password) return { error: "Enter your email and password." };
  if (password.length < 6) return { error: "Password must be at least 6 characters." };

  const supabase = await createServerSupabase();
  const { data, error } = await supabase.auth.signUp({ email, password });
  if (error) return { error: friendly(error.message) };

  if (!data.session) {
    return { info: "Check your email to confirm your account, then sign in." };
  }
  redirect("/profiles");
}

export async function signOut(): Promise<void> {
  const supabase = await createServerSupabase();
  await supabase.auth.signOut();
  redirect("/login");
}
