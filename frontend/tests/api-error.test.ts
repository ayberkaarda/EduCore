import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { ApiError, NETWORK_ERROR_CODE, problemToApiError, toApiError } from '../app/lib/api-error'
import { describeApiError } from '../app/lib/error-messages'

function axiosFailure(status: number, data: unknown, headers: Record<string, string> = {}) {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, {}, {
    status,
    statusText: '',
    data,
    headers: new AxiosHeaders(headers),
    config,
  })
}

describe('ApiError parsing of application/problem+json', () => {
  it('reads status, code, title, detail and field errors of a validation problem', () => {
    const error = toApiError(axiosFailure(400, {
      type: '/problems/request/invalid',
      title: 'The request is invalid.',
      status: 400,
      detail: 'One or more request values are missing or invalid.',
      instance: '/api/v1/admin/courses',
      code: 'request/invalid',
      errors: [{ field: 'name', code: 'size' }, { field: 'term', code: 'pattern' }, { field: 7, code: 'size' }, 'junk'],
    }, { 'Content-Type': 'application/problem+json' }))

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.code).toBe('request/invalid')
    expect(error.title).toBe('The request is invalid.')
    expect(error.detail).toBe('One or more request values are missing or invalid.')
    expect(error.instance).toBe('/api/v1/admin/courses')
    expect(error.fieldErrors).toEqual([{ field: 'name', code: 'size' }, { field: 'term', code: 'pattern' }])
    expect(error.codesFor('name')).toEqual(['size'])
  })

  it('derives the code from the type URI when the code member is missing', () => {
    const error = problemToApiError(409, { type: 'https://educore.example/problems/account/last-admin', title: 'Conflict.' })
    expect(error.code).toBe('account/last-admin')
  })

  it('keeps password policy violations, Retry-After and the 5xx correlation id', () => {
    const policy = problemToApiError(400, { code: 'auth/password-policy', violations: ['too_short', 'common_password', 3] })
    expect(policy.violations).toEqual(['too_short', 'common_password'])

    const locked = toApiError(axiosFailure(423, { code: 'auth/account-locked', title: 'Locked.' }, { 'Retry-After': '120' }))
    expect(locked.retryAfterSeconds).toBe(120)
    expect(describeApiError(locked, 'fallback')).toBe('Too many failed sign-ins. The account is locked for now. Try again in 120 seconds.')

    const crash = problemToApiError(500, { code: 'server/internal-error', title: 'Internal error.', correlationId: 'req-42' })
    expect(describeApiError(crash, 'fallback')).toBe('The server could not complete the request (reference req-42).')
  })

  it('parses a problem body that arrives as a string and tolerates bodies without problem members', () => {
    expect(problemToApiError(404, '{"code":"course/not-found","title":"Not found."}').code).toBe('course/not-found')
    const bare = problemToApiError(403, '<html>forbidden</html>')
    expect(bare.code).toBe('http/403')
    expect(bare.title).toBe('Access is denied.')
    expect(problemToApiError(502, null).code).toBe('server/internal-error')
  })

  it('maps a request without a response to a network error', () => {
    const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
    const error = toApiError(new AxiosError('Network Error', 'ERR_NETWORK', config, {}))
    expect(error.status).toBe(0)
    expect(error.code).toBe(NETWORK_ERROR_CODE)
    expect(describeApiError(error, 'fallback')).toMatch(/unreachable/)
  })

  it('maps known codes to sentences and falls back for unknown ones', () => {
    expect(describeApiError(problemToApiError(409, { code: 'account/student-number-taken' }), 'x')).toBe('This student number is already in use.')
    expect(describeApiError(problemToApiError(418, { code: 'teapot/unknown' }), 'Could not save.')).toBe('Could not save.')
  })
})
