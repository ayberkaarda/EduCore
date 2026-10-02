// Catalog search island: the only script on the public pages. scripts/postbuild-public.mjs compiles this file
// on its own (no React, no imports) and adds it to the prerendered catalog pages as a content-hashed module.
// Without JavaScript the form stays hidden and the full, prerendered list remains readable.

function normalize(text: string, locale: string): string {
  return text
    .toLocaleLowerCase(locale)
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/ı/g, 'i')
    .replace(/\s+/g, ' ')
    .trim()
}

export function enhanceCatalogSearch(root: Document): boolean {
  const form = root.querySelector<HTMLFormElement>('form[data-catalog-search]')
  const input = form?.querySelector<HTMLInputElement>('input[type="search"]')
  const list = root.querySelector<HTMLElement>('[data-catalog-list]')
  const status = root.querySelector<HTMLElement>('[data-catalog-status]')
  if (!form || !input || !list || !status) return false

  const locale = root.documentElement.lang || 'tr'
  const noMatch = form.dataset.noMatch ?? ''
  const items = Array.from(list.querySelectorAll<HTMLElement>(':scope > li')).map(item => ({
    item,
    text: normalize(item.textContent ?? '', locale),
  }))

  const apply = () => {
    const terms = normalize(input.value, locale).split(' ').filter(Boolean)
    let visible = 0
    for (const entry of items) {
      const match = terms.every(term => entry.text.includes(term))
      entry.item.hidden = !match
      if (match) visible += 1
    }
    list.hidden = visible === 0
    status.textContent = visible === 0 ? noMatch : ''
  }

  form.addEventListener('submit', event => event.preventDefault())
  input.addEventListener('input', apply)
  form.hidden = false
  return true
}

if (typeof document !== 'undefined') enhanceCatalogSearch(document)
