// Response shapes from docs/api/ROUTES.md (section "Shapes").

export type Role = 'ADMIN' | 'USER'
export type AccountStatus = 'ACTIVE' | 'DEACTIVATED' | 'PENDING_DELETION'

export interface UserView {
  id: number
  firstName: string
  role: Role
  mustChangePassword: boolean
  status: 'ACTIVE' | 'PENDING_DELETION'
}

export interface SessionPayload {
  accessToken: string
  expiresIn: number
  user: UserView
}

export interface Profile {
  status: 'ACTIVE' | 'PENDING_DELETION'
  deleteAfter: string | null
  id: number
  username: string
  firstName: string
  lastName: string | null
  studentNumber: string | null
  role: Role
}

export interface Account {
  status: AccountStatus
  deleteAfter: string | null
  id: number
  username: string
  firstName: string
  lastName: string | null
  studentNumber: string | null
  role: Role
  ipAddress: string | null
}

export interface CreatedStudent extends Omit<Account, 'status' | 'deleteAfter'> {
  temporaryPassword: string
}

export interface SecurityEvent {
  id: number
  type: string
  actorAccountId: number | null
  targetAccountId: number | null
  actorPseudonym: string | null
  targetPseudonym: string | null
  ip: string | null
  requestId: string | null
  at: string
  details: Record<string, unknown> | null
}

export interface Course {
  id: number
  name: string
  term: string | null
  instructor: string | null
}

export type IpRuleType = 'STATIC' | 'RANGE' | 'CIDR'

export interface IpAllocation {
  id: number
  type: IpRuleType
  originalValue: string
}

export type IpRule = IpAllocation

export interface JobLog {
  id: number
  fileName: string
  entityType: string | null
  status: 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | null
  reason: string | null
  readRecords: number
  importedFileId: number | null
  startedAt: string | null
  finishedAt: string | null
  successfulRecords: number
  failedRecords: number
  createdAt: string
}

export interface CityWeather {
  status: 'OK' | 'STALE' | 'UNAVAILABLE'
  observedAt: string | null
  city: string
  temperature: number | null
  windSpeed: number | null
  weatherCode: number | null
  description: string | null
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
