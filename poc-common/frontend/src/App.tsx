import { useMemo, useState } from 'react'
import { batch } from './api'
import { ExecutionDetail } from './ExecutionDetail'
import { browserTimeZone } from './format'
import { Executions } from './Executions'
import { scheduleBackend } from './scheduleBackends'
import { Schedules } from './Schedules'
import { usePolling } from './usePolling'

const SCHEDULES_REFRESH_MS = 10_000
const EXECUTIONS_REFRESH_MS = 3_000

export function App() {
  const server = usePolling(batch.server, null, [])
  const scheduler = server.data?.scheduler
  const backend = useMemo(() => (scheduler ? scheduleBackend(scheduler.scheduleApi) : undefined), [scheduler])
  const schedules = usePolling(async () => (backend ? backend.load() : undefined), SCHEDULES_REFRESH_MS, [backend])
  const [jobFilter, setJobFilter] = useState('')
  const executions = usePolling(() => batch.executions(jobFilter || undefined), EXECUTIONS_REFRESH_MS, [jobFilter])
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const jobNames = (schedules.data ?? []).map((job) => job.name)

  /** After "Run now", show the new execution as soon as Spring Batch has created it. */
  async function selectNewExecution(jobName: string) {
    const known = new Set((executions.data ?? []).map((e) => e.jobExecutionId))
    // the scheduler may need a few seconds to pick the run up (e.g. JobRunr polls every 5 s)
    for (let attempt = 0; attempt < 30; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 500))
      const latest = (await batch.executions(jobName)).find((e) => !known.has(e.jobExecutionId))
      if (latest) {
        setSelectedId(latest.jobExecutionId)
        break
      }
    }
    executions.reload()
    schedules.reload()
  }

  return (
    <>
      <header className="app-header">
        <h1>Batch Console</h1>
        <span className="muted">{scheduler ? `${scheduler.name} + Spring Batch` : 'Spring Batch'}</span>
        <span className="spacer" />
        {server.data && (
          <span className="muted small">
            Server time zone: {server.data.timeZone} · yours: {browserTimeZone()}
          </span>
        )}
        {scheduler?.links.map((link) => (
          <a key={link.url} href={link.url}>
            {link.label}
          </a>
        ))}
      </header>
      <main>
        {server.error && <p className="error">Could not reach the backend: {server.error}</p>}
        {scheduler && backend && (
          <Schedules
            jobs={schedules.data}
            error={schedules.error}
            backend={backend}
            dialect={scheduler.cronDialect}
            schedulerName={scheduler.name}
            serverTimeZone={server.data?.timeZone}
            onChanged={schedules.reload}
            onStarted={selectNewExecution}
          />
        )}
        <Executions
          executions={executions.data}
          error={executions.error}
          jobNames={jobNames}
          jobFilter={jobFilter}
          onJobFilterChange={setJobFilter}
          selectedId={selectedId}
          onSelect={setSelectedId}
        />
        {selectedId !== null && (
          <ExecutionDetail
            key={selectedId}
            id={selectedId}
            onClose={() => setSelectedId(null)}
            onChanged={executions.reload}
          />
        )}
      </main>
    </>
  )
}
