import type { CapacitorConfig } from '@capacitor/cli';

// Where the app's API calls go. In the browser the app uses the relative '/api' (dev-server
// proxy); a native app has no proxy, so the backend URL is compiled in. Default: the host
// machine as seen from the Android emulator. See scripts/mobile-env.mjs and the README.
const apiUrl = process.env['MOBILE_API_URL'] ?? 'http://10.0.2.2:8080/api';
// Plain-HTTP backends are only for local development (emulator / LAN); an https:// backend
// (e.g. a public Codespaces port) needs neither exception.
const insecureApi = apiUrl.startsWith('http://');

const config: CapacitorConfig = {
  appId: 'tn.houwiya.app',
  appName: 'Houwiya',
  webDir: 'dist/frontend/browser',
  server: {
    androidScheme: 'https',
    cleartext: insecureApi
  },
  android: {
    allowMixedContent: insecureApi
  }
};

export default config;
