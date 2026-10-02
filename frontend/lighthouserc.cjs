// Lighthouse CI for the prerendered public pages (docs/seo/PERFORMANCE.md). Serves dist/client with
// scripts/serve-dist.mjs (nginx-like routing, gzip, the production security headers) and audits the landing page,
// the catalog and one course page. Run after `npm run build:mock` (or a real build with
// PUBLIC_SITE_URL=http://localhost:4173):  CHROME_PATH=<chrome> npm run lighthouse
const PORT = process.env.LHCI_PORT || '4173'
const base = `http://localhost:${PORT}`

module.exports = {
  ci: {
    collect: {
      startServerCommand: `node scripts/serve-dist.mjs`,
      startServerReadyPattern: 'serve-dist: dist/client on',
      startServerReadyTimeout: 20000,
      url: [`${base}/`, `${base}/courses`, `${base}/courses/${process.env.LHCI_COURSE_SLUG || 'dogrusal-cebir'}`],
      numberOfRuns: 3,
      chromePath: process.env.CHROME_PATH || undefined,
      settings: {
        chromeFlags: '--headless=new --no-sandbox',
      },
    },
    assert: {
      assertions: {
        'categories:performance': ['error', { minScore: 0.9 }],
        'categories:seo': ['error', { minScore: 0.95 }],
        'categories:accessibility': ['error', { minScore: 0.95 }],
        'categories:best-practices': ['error', { minScore: 0.95 }],
        'cumulative-layout-shift': ['error', { maxNumericValue: 0.1 }],
        'largest-contentful-paint': ['error', { maxNumericValue: 2500 }],
      },
    },
    upload: {
      target: 'filesystem',
      outputDir: './dist/lighthouse',
    },
  },
}
