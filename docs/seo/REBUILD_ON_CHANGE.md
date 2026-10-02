# Rebuilding the public site when courses change

The public pages are static (D-03 A). A course that is published, edited, unpublished or deleted appears on the
site after the next frontend build. Until then the backend `/sitemap.xml` and `/api/v1/public/**` are already
current; only the HTML lags.

## Automatic: `course.updated` webhook → CI dispatch

EduCore sends `course.updated` (`{courseId, action: CREATED | UPDATED | DELETED}`) for every ADMIN course change
(`docs/integrations/WEBHOOKS.md`). GitHub's `repository_dispatch` API needs an `Authorization: Bearer` token and
its own body (`{event_type, client_payload}`), which EduCore webhooks cannot send. A small relay therefore sits
in between:

```
EduCore --(signed POST, course.updated)--> relay --(verify signature, POST repository_dispatch)--> GitHub Actions
```

1. Deploy the relay (any HTTPS endpoint with a public address; EduCore refuses private addresses).
2. As ADMIN create the subscription: `POST /api/v1/admin/webhooks` with
   `{"url": "https://relay.example.org/educore", "events": ["course.updated"]}`; store the returned `secret` (shown
   once) in the relay as `EDUCORE_WEBHOOK_SECRET`.
3. Give the relay a fine-grained GitHub token with "Contents: read and write" on the repository
   (`GITHUB_DISPATCH_TOKEN`); `repository_dispatch` requires it.

Relay (Node 22, no dependencies). It verifies the signature exactly as WEBHOOKS.md specifies, rejects replays
older than 5 minutes, ignores duplicate deliveries and answers within the 5-second budget:

```js
import { createHmac, timingSafeEqual } from 'node:crypto'
import { createServer } from 'node:http'

const secret = process.env.EDUCORE_WEBHOOK_SECRET
const token = process.env.GITHUB_DISPATCH_TOKEN
const repo = process.env.GITHUB_REPOSITORY // "owner/name"
const seen = new Set()

function valid(timestamp, signature, rawBody) {
  if (!/^\d+$/.test(timestamp ?? '') || !signature) return false
  if (Math.abs(Date.now() / 1000 - Number(timestamp)) > 300) return false
  const expected = 'v1=' + createHmac('sha256', Buffer.from(secret, 'utf8')).update(`${timestamp}.`).update(rawBody).digest('hex')
  const a = Buffer.from(expected, 'ascii')
  const b = Buffer.from(signature, 'ascii')
  return a.length === b.length && timingSafeEqual(a, b)
}

createServer((request, response) => {
  const chunks = []
  request.on('data', chunk => chunks.push(chunk))
  request.on('end', () => {
    const rawBody = Buffer.concat(chunks)
    if (request.method !== 'POST' || !valid(request.headers['x-educore-timestamp'], request.headers['x-educore-signature'], rawBody)) {
      response.writeHead(401).end()
      return
    }
    const delivery = request.headers['x-educore-delivery']
    response.writeHead(202).end()
    if (request.headers['x-educore-event'] !== 'course.updated' || seen.has(delivery)) return
    seen.add(delivery)
    fetch(`https://api.github.com/repos/${repo}/dispatches`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
      body: JSON.stringify({ event_type: 'educore-course-updated', client_payload: { delivery } }),
    }).catch(error => console.error('dispatch failed', error.message))
  })
}).listen(Number(process.env.PORT ?? 8080))
```

Workflow side (in the CI workflow), triggered by the dispatch:

```yaml
on:
  repository_dispatch:
    types: [educore-course-updated]
concurrency:            # many course edits in a row collapse into one build
  group: public-site
  cancel-in-progress: true
jobs:
  public-site:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22 }
      - run: npm ci
        working-directory: frontend
      - run: npm run build
        working-directory: frontend
        env:
          PUBLIC_API_URL: ${{ secrets.EDUCORE_PUBLIC_API_URL }}   # the production backend origin
          PUBLIC_SITE_URL: ${{ vars.EDUCORE_SEO_BASE_URL }}
      # then build and publish the frontend image as in the release job
```

Notes:

- The payload carries only the course id; the build reads the published catalog itself, so a dispatch with a
  stale or duplicated event is harmless (the build is idempotent).
- Events lost between commit and enqueue (WEBHOOKS.md, no outbox yet) are covered by a scheduled build (for
  example nightly `schedule:` on the same workflow).

## Manual rebuild

```bash
cd frontend
PUBLIC_API_URL=https://educore.example.org PUBLIC_SITE_URL=https://educore.example.org npm run build
```

(`/api/v1/public/**` is anonymous and served through the same origin.) Then rebuild and redeploy the frontend
image. Locally without a backend: `npm run build:mock`.
