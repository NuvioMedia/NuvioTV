// Type-only re-export — the actual Supabase client is created in each web app
// (web/app, web/panel, web/tv-login) so this package stays dependency-free and
// doesn't need its own node_modules.

export interface SupabaseEnv {
  url: string;
  anonKey: string;
}
