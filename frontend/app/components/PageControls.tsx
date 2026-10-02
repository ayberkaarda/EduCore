import type { PageResponse } from '../lib/types'
export default function PageControls({ data, page, onPage, pending = false }: { data?: Pick<PageResponse<unknown>, 'totalPages' | 'totalElements'>; page: number; onPage: (page: number) => void; pending?: boolean }) {
  return <div className="modal-actions" aria-label="Pagination"><span>{data?.totalElements ?? 0} results · Page {page + 1} of {Math.max(1, data?.totalPages ?? 1)}</span><button type="button" className="btn-secondary" disabled={pending || page === 0} onClick={() => onPage(page - 1)}>Previous page</button><button type="button" className="btn-secondary" disabled={pending || !data || page + 1 >= data.totalPages} onClick={() => onPage(page + 1)}>Next page</button></div>
}
