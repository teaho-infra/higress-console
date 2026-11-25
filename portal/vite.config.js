import { defineConfig } from "vite";
export default defineConfig({
  // Shared Console source must use the standalone host's React instance.
  resolve: { dedupe: ["react", "react-dom"] },
  server: {
    proxy: {
      "/api": { target: "http://127.0.0.1:18890", changeOrigin: false },
    },
  },
});
