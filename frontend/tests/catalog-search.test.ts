import { beforeEach, describe, expect, it } from 'vitest'
import { enhanceCatalogSearch } from '../app/public-site/islands/catalog-search'

function setBody(html: string) {
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  document.body.replaceChildren(...parsed.body.childNodes)
}

function catalog() {
  document.documentElement.lang = 'tr'
  setBody(`
    <form data-catalog-search="" data-no-match="Aramanızla eşleşen ders yok." hidden><label for="q">Derslerde ara</label><input id="q" type="search"></form>
    <p data-catalog-status=""></p>
    <ul data-catalog-list="">
      <li><h2>Doğrusal Cebir</h2><p>2026 Güz · Doç. Dr. Mert Öztürk</p></li>
      <li><h2>İşletim Sistemleri</h2><p>2026 Güz · Dr. Can Yılmaz</p></li>
      <li><h2>Database Systems</h2><p>2027 Spring</p></li>
    </ul>`)
}

function type(value: string) {
  const input = document.querySelector('input') as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input'))
}

function visible() {
  return [...document.querySelectorAll('li')].filter(item => !item.hidden).map(item => item.querySelector('h2')?.textContent)
}

describe('catalog search island', () => {
  beforeEach(catalog)

  it('reveals the form only when the script runs', () => {
    expect((document.querySelector('form') as HTMLFormElement).hidden).toBe(true)
    expect(enhanceCatalogSearch(document)).toBe(true)
    expect((document.querySelector('form') as HTMLFormElement).hidden).toBe(false)
  })

  it('matches Turkish names without diacritics and in any case', () => {
    enhanceCatalogSearch(document)
    type('dogrusal')
    expect(visible()).toEqual(['Doğrusal Cebir'])
    type('ISLETIM')
    expect(visible()).toEqual(['İşletim Sistemleri'])
    type('2026 güz')
    expect(visible()).toEqual(['Doğrusal Cebir', 'İşletim Sistemleri'])
    type('')
    expect(visible()).toHaveLength(3)
  })

  it('announces when nothing matches', () => {
    enhanceCatalogSearch(document)
    type('kimya')
    expect(visible()).toEqual([])
    expect(document.querySelector('[data-catalog-status]')?.textContent).toBe('Aramanızla eşleşen ders yok.')
    expect((document.querySelector('[data-catalog-list]') as HTMLElement).hidden).toBe(true)
  })

  it('does nothing on pages without the catalog', () => {
    setBody('<main><h1>Gizlilik</h1></main>')
    expect(enhanceCatalogSearch(document)).toBe(false)
  })
})
