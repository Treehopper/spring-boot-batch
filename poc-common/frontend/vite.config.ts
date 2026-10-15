import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Built into the poc-common jar (META-INF/resources/batch-console), so every PoC serves it under /batch-console/.
// For frontend development, run the app on :8080 and `npm run dev`; API calls are proxied.
export default defineConfig({
  base: '/batch-console/',
  plugins: [react()],
  build: {
    outDir: '../target/classes/META-INF/resources/batch-console',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/quartz-manager': 'http://localhost:8080',
      '/batch-console/api': 'http://localhost:8080',
    },
  },
})
