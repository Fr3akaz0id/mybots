import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

import { cloudflare } from "@cloudflare/vite-plugin";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), cloudflare()],
  resolve: {
    // the playground runs on the library's source, so edits to it hot-reload here
    alias: { 'robot-heads': fileURLToPath(new URL('../src/index.ts', import.meta.url)) },
    // and that source must share the site's one copy of React
    dedupe: ['react', 'react-dom'],
  },
})
