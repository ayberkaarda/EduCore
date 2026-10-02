import type { Server } from 'node:http'
import type { PublicCourse } from '../app/public-site/public-api.mjs'

export declare const MOCK_COURSES: readonly PublicCourse[]
export declare function createMockPublicApi(options?: { siteUrl?: string }): Server
export declare function startMockPublicApi(options?: { port?: number; siteUrl?: string }): Promise<{ server: Server; url: string }>
