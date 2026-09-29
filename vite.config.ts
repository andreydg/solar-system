/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8080",
    },
  },
  test: {
    // Run the R3F packages as ES modules, like the browser does. Their `main` entries are
    // CommonJS, which load a second, CommonJS copy of three (R3F then treats our ES math objects,
    // e.g. a Quaternion prop, as a foreign type) and, when mixed, a second R3F (so drei's hooks
    // can't find the test renderer's canvas). All three must resolve to their ES builds.
    alias: {
      "@react-three/drei": "@react-three/drei/index.js",
      "@react-three/fiber": "@react-three/fiber/dist/react-three-fiber.esm.js",
      "@react-three/test-renderer": "@react-three/test-renderer/dist/react-three-test-renderer.esm.js",
    },
    server: {
      deps: {
        inline: [/@react-three\//],
      },
    },
  },
});
