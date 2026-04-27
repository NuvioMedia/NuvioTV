import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { TanStackRouterVite } from "@tanstack/router-vite-plugin";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [
    TanStackRouterVite({
      routesDirectory: "./src/routes",
      generatedRouteTree: "./src/routeTree.gen.ts",
      autoCodeSplitting: true,
    }),
    react(),
  ],
  resolve: {
    alias: [
      // More specific subpaths must come before "@omnio/shared" so the resolver
      // doesn't collapse "@omnio/shared/addon" into "shared/src/index.ts/addon".
      {
        find: "@omnio/shared/supabase",
        replacement: path.resolve(__dirname, "../shared/src/supabase/index.ts"),
      },
      {
        find: "@omnio/shared/addon",
        replacement: path.resolve(__dirname, "../shared/src/addon/index.ts"),
      },
      {
        find: "@omnio/shared/proxy",
        replacement: path.resolve(__dirname, "../shared/src/proxy/index.ts"),
      },
      {
        find: "@omnio/shared/codec",
        replacement: path.resolve(__dirname, "../shared/src/codec/index.ts"),
      },
      {
        find: "@omnio/shared",
        replacement: path.resolve(__dirname, "../shared/src/index.ts"),
      },
      { find: "@", replacement: path.resolve(__dirname, "src") },
    ],
  },
  server: {
    port: 5173,
  },
  build: {
    target: "es2022",
    sourcemap: true,
  },
  optimizeDeps: {
    exclude: ["jassub"],
  },
});
