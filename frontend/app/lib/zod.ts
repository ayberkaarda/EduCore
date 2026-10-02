import { z } from 'zod'

// No code generation: zod's JIT probes `new Function`, which `script-src 'self'` (no 'unsafe-eval') reports as a
// CSP violation. Every schema imports z from here so the setting is applied before the first parse.
z.config({ jitless: true })

export { z }
