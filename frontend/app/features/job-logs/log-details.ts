import type { JobLog } from '../../lib/types'
/** Download the selected job summaries; record entries are available separately in Details. */
export function downloadLogs(logs: JobLog[]): void {
  for (const log of logs) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(log, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `Log_${log.entityType}_${log.fileName}_ID-${log.id}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  }
}
