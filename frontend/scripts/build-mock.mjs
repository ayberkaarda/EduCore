// `npm run build:mock`: the production build against the bundled mock public API (scripts/mock-public-api.mjs),
// for local runs without the backend. Starts the mock on a free port, runs `npm run build` with PUBLIC_API_URL
// pointing to it (PUBLIC_SITE_URL defaults to http://localhost:4173, the origin used by `npm run serve:dist` and
// Lighthouse CI), then the prerender checks, and exits with the first non-zero status.
import { spawn } from 'node:child_process'
import { startMockPublicApi } from './mock-public-api.mjs'

const siteUrl = process.env.PUBLIC_SITE_URL || 'http://localhost:4173'
const { server, url } = await startMockPublicApi({ port: 0, siteUrl })
console.log(`build:mock: mock public API on ${url}, PUBLIC_SITE_URL=${siteUrl}`)

function run(script) {
  return new Promise(resolve => {
    const child = spawn(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', script], {
      stdio: 'inherit',
      shell: process.platform === 'win32',
      env: { ...process.env, PUBLIC_API_URL: url, PUBLIC_SITE_URL: siteUrl },
    })
    child.on('exit', code => resolve(code ?? 1))
  })
}

let status = await run('build')
if (status === 0) status = await run('test:prerender')
server.close()
process.exit(status)
