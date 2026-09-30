export const environment = {
  production: false,
  // Relative on purpose: `ng serve` proxies /api to the backend (proxy.conf.json), so the
  // same build works on localhost and behind a GitHub Codespaces forwarded URL.
  apiUrl: '/api'
};
