import { useState } from 'react'
import { batch, type Key } from './api'
import { ExecutionDetail } from './ExecutionDetail'
import { Executions } from './Executions'
import { loadSchedules, Schedules } from './Schedules'
import { usePolling } from './usePolling'

const SCHEDULES_REFRESH_MS = 10_000
const EXECUTIONS_REFRESH_MS = 3_000

export function App() {
  const schedules = usePolling(loadSchedules, SCHEDULES_REFRESH_MS, [])
  const [jobFilter, setJobFilter] = useState('')
  const executions = usePolling(() => batch.executions(jobFilter || undefined), EXECUTIONS_REFRESH_MS, [jobFilter])
  const [selectedId, setSelectedId] = useState<number | null>(null)

  const jobNames = (schedules.data ?? []).map((row) => row.job.jobKeyDTO.name)

  /** After "Run now", show the new execution as soon as Spring Batch has created it. */
  async function selectNewExecution(job: Key) {
    const known = new Set((executions.data ?? []).map((e) => e.jobExecutionId))
    for (let attempt = 0; attempt < 10; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 500))
      const latest = (await batch.executions(job.name)).find((e) => !known.has(e.jobExecutionId))
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
        <span className="muted">Custom UI on the Quartz Manager API and Spring Batch</span>
        <span className="spacer" />
        <a href="/quartz-manager-ui/">Quartz Manager UI</a>
      </header>
      <main>
        <Schedules
          rows={schedules.data}
          error={schedules.error}
          onChanged={schedules.reload}
          onStarted={selectNewExecution}
        />
        <div className={selectedId === null ? 'executions' : 'executions with-detail'}>
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
        </div>
      </main>
    </>
  )
}
