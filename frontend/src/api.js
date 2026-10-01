export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081').replace(/\/+$/, '')
const v1 = `${API_BASE_URL}/api/v1`
const item = (path, id) => `${path}/${encodeURIComponent(id)}`
export const api = {
    auth: `${v1}/auth`, login: `${v1}/auth/login`, me: `${v1}/me`,
    enrollments: `${v1}/me/enrollments`, courses: `${v1}/courses`, weather: `${v1}/weather`,
    accounts: `${v1}/admin/accounts`, students: `${v1}/admin/accounts/students`,
    account: id => item(`${v1}/admin/accounts`, id),
    accountRole: id => `${item(`${v1}/admin/accounts`, id)}/role`,
    accountEnrollments: id => `${item(`${v1}/admin/accounts`, id)}/enrollments`,
    adminCourses: `${v1}/admin/courses`, course: id => item(`${v1}/admin/courses`, id),
    ipRules: `${v1}/admin/ip-rules`, ipRule: id => item(`${v1}/admin/ip-rules`, id),
    jobLogs: `${v1}/admin/job-logs`,
}
export function apiError(error, fallback) {
    const data = error?.response?.data
    for (const message of [data?.title, data?.error, data?.message]) {
        if (typeof message === 'string' && message.trim()) return message
    }
    return fallback
}
