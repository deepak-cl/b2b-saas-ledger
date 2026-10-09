import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/realms": "http://localhost:8180",
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
