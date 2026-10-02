import type { AiCrawlerPolicy } from './build-config.mjs'
import type { PublicCourse, SiteFacts } from './public-api.mjs'
import type { Locale } from './site-map.mjs'

export interface PageSummary {
  readonly title: string
  readonly description: string
}

export interface ContactLink {
  readonly title: string
  readonly url: string
  readonly summary: string
}

export interface MarkdownDocument {
  readonly url: string
  readonly locale: Locale
  readonly markdown: string
}

export declare function robotsTxt(input: { siteUrl: string; aiCrawlers: AiCrawlerPolicy }): string
export declare function w3cDateTime(iso: string): string
export declare function sitemapXml(input: { siteUrl: string; facts: SiteFacts; courses: readonly PublicCourse[] }): string
export declare function llmsTxt(input: {
  siteUrl: string
  facts: SiteFacts
  courses: readonly PublicCourse[]
  pages: ReadonlyMap<string, PageSummary>
  contact: readonly ContactLink[]
}): string
export declare function mainToMarkdown(main: Element, siteUrl: string): string
export declare function llmsFullTxt(input: { facts: SiteFacts; documents: readonly MarkdownDocument[] }): string
