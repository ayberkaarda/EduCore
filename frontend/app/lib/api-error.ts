import { isAxiosError } from 'axios'

/** One entry of a problem's `errors` array (400 validation only): JSON property path and constraint code. */
export interface FieldError {
  field: string
  code: string
}

export interface ApiErrorInit {
  status: number
  code: string
  title: string
  detail?: string
  type?: string
  instance?: string
  fieldErrors?: FieldError[]
  violations?: string[]
  correlationId?: string
  retryAfterSeconds?: number
}

/**
 * Every failed API call is turned into this error. The backend answers with RFC 9457 problem details
 * (`application/problem+json`); the parser is defensive because some responses (network failures, proxies,
 * pre-P4 shapes) carry no problem body. `status` is 0 when no HTTP response was received.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly title: string
  readonly detail?: string
  readonly type?: string
  readonly instance?: string
  readonly fieldErrors: FieldError[]
  readonly violations: string[]
  readonly correlationId?: string
  readonly retryAfterSeconds?: number

  constructor(init: ApiErrorInit) {
    super(init.title)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.title = init.title
    this.detail = init.detail
    this.type = init.type
    this.instance = init.instance
    this.fieldErrors = init.fieldErrors ?? []
    this.violations = init.violations ?? []
    this.correlationId = init.correlationId
    this.retryAfterSeconds = init.retryAfterSeconds
  }

  /** Constraint codes the server reported for one field (e.g. `["pattern"]`). */
  codesFor(field: string): string[] {
    return this.fieldErrors.filter(error => error.field === field).map(error => error.code)
  }
}

export const NETWORK_ERROR_CODE = 'network/unreachable'
export const CANCELED_ERROR_CODE = 'request/canceled'

const PROBLEM_PATH = '/problems/'

const nonEmptyString = (value: unknown): string | undefined =>
  typeof value === 'string' && value.trim() !== '' ? value : undefined

/** `type` is `<base-url>/problems/<code>`; the code is everything after `/problems/`. */
function codeFromType(type: string | undefined): string | undefined {
  if (!type) return undefined
  const index = type.indexOf(PROBLEM_PATH)
  return index >= 0 ? nonEmptyString(type.slice(index + PROBLEM_PATH.length)) : undefined
}

function parseFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    if (item && typeof item === 'object') {
      const field = nonEmptyString((item as Record<string, unknown>).field)
      const code = nonEmptyString((item as Record<string, unknown>).code)
      if (field && code) return [{ field, code }]
    }
    return []
  })
}

function parseStringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function parseRetryAfter(value: unknown): number | undefined {
  const raw = Array.isArray(value) ? value[0] : value
  if (typeof raw !== 'string' && typeof raw !== 'number') return undefined
  const seconds = Number(raw)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined
}

function parseBody(data: unknown): Record<string, unknown> {
  if (data && typeof data === 'object' && !Array.isArray(data)) return data as Record<string, unknown>
  if (typeof data === 'string' && data.trim().startsWith('{')) {
    try {
      const parsed: unknown = JSON.parse(data)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, unknown>
    } catch {
      return {}
    }
  }
  return {}
}

const DEFAULT_TITLES: Record<number, string> = {
  400: 'The request is invalid.',
  401: 'Authentication is required.',
  403: 'Access is denied.',
  404: 'The resource was not found.',
  409: 'The request conflicts with the current state.',
  413: 'The request is too large.',
  423: 'The account is locked.',
  429: 'Too many requests.',
}

/** Builds an ApiError from an HTTP status, a (possibly absent) body and response headers. */
export function problemToApiError(status: number, data: unknown, headers: Record<string, unknown> = {}): ApiError {
  const body = parseBody(data)
  const type = nonEmptyString(body.type)
  const code = nonEmptyString(body.code) ?? codeFromType(type) ?? (status >= 500 ? 'server/internal-error' : `http/${status}`)
  const title = nonEmptyString(body.title) ?? nonEmptyString(body.error) ?? nonEmptyString(body.message)
    ?? DEFAULT_TITLES[status] ?? (status >= 500 ? 'The server could not complete the request.' : 'The request failed.')
  return new ApiError({
    status,
    code,
    title,
    type,
    detail: nonEmptyString(body.detail),
    instance: nonEmptyString(body.instance),
    fieldErrors: parseFieldErrors(body.errors),
    violations: parseStringList(body.violations),
    correlationId: nonEmptyString(body.correlationId) ?? nonEmptyString(headers['x-request-id']),
    retryAfterSeconds: parseRetryAfter(headers['retry-after']),
  })
}

/** Normalises anything thrown by an API call into an ApiError. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  if (isAxiosError(error)) {
    if (error.code === 'ERR_CANCELED') {
      return new ApiError({ status: 0, code: CANCELED_ERROR_CODE, title: 'The request was canceled.' })
    }
    if (error.response) {
      const headers: Record<string, unknown> = {}
      const raw: unknown = error.response.headers ?? {}
      const plain = raw && typeof (raw as { toJSON?: unknown }).toJSON === 'function'
        ? (raw as { toJSON: () => Record<string, unknown> }).toJSON()
        : (raw as Record<string, unknown>)
      for (const [key, value] of Object.entries(plain)) headers[key.toLowerCase()] = value
      return problemToApiError(error.response.status, error.response.data, headers)
    }
    return new ApiError({ status: 0, code: NETWORK_ERROR_CODE, title: 'The server is unreachable.' })
  }
  return new ApiError({ status: 0, code: 'client/error', title: 'Something went wrong.' })
}
