export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '–'
  if (ms < 1000) return `${ms} ms`
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${(ms / 1000).toFixed(1)} s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ${seconds % 60} s`
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`
}

/** A timestamp in the browser's time zone, or in the given one. */
export function formatTime(iso: string | null | undefined, timeZone?: string): string {
  if (!iso) return '–'
  return new Date(iso).toLocaleString(undefined, {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/** "trigger:batch.importCustomersJob-cron" → "Trigger importCustomersJob-cron", "api" → "API (UC4)" */
export function formatStartedBy(startedBy: string | null): string {
  if (!startedBy) return '–'
  if (startedBy === 'api') return 'API (UC4)'
  if (startedBy === 'manual') return 'Manual'
  if (startedBy.startsWith('trigger:')) return `Trigger ${startedBy.slice('trigger:'.length).replace(/^[^.]*\./, '')}`
  return startedBy
}

export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}
