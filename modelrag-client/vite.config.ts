import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

function packageChunk(id: string) {
  if (!id.includes('node_modules')) return undefined;
  const normalized = id.replaceAll('\\', '/');
  if (normalized.includes('/react/') || normalized.includes('/react-dom/') || normalized.includes('/scheduler/')) return 'vendor-react';
  if (normalized.includes('/echarts/') || normalized.includes('/zrender/')) return 'vendor-charts';
  if (normalized.includes('/antd/') || normalized.includes('/@ant-design/') || normalized.includes('/rc-') || normalized.includes('/@rc-component/')) return 'vendor-ui';
  return 'vendor-utils';
}

const backendUrl = process.env.VITE_BACKEND_URL || 'http://localhost:8091';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': backendUrl }
  },
  build: {
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: packageChunk
      }
    }
  }
});
