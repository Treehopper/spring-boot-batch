import type { Execution } from './api'
import { formatDuration, formatStartedBy, formatTime } from './format'

interface Props {
  executions: Execution[] | undefined
  error: string | undefined
  jobNames: string[]
  jobFilter: string
  onJobFilterChange: (jobName: string) => void
  selectedId: number | null
  onSelect: (id: number) => void
}

export function Executions({ executions, error, jobNames, jobFilter, onJobFilterChange, selectedId, onSelect }: Props) {
  return (
    <section className="card">
      <header className="card-header">
        <h2>Executions</h2>
        <span className="muted">Spring Batch history: every run, however it was started</span>
        <select value={jobFilter} onChange={(e) => onJobFilterChange(e.target.value)} aria-label="Filter by job">
          <option value="">All jobs</option>
          {jobNames.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </header>
      {error && <p className="error">Could not load executions: {error}</p>}
      {!executions && !error && <p className="muted">Loading…</p>}
      {executions && executions.length === 0 && <p className="muted">No executions yet.</p>}
      {executions && executions.length > 0 && (
        <div className="scroll">
          <table className="selectable">
            <thead>
              <tr>
                <th>#</th>
                <th>Job</th>
                <th>Status</th>
                <th>Started by</th>
                <th>Start</th>
                <th>Duration</th>
              </tr>
            </thead>
            <tbody>
              {executions.map((execution) => (
                <tr
                  key={execution.jobExecutionId}
                  className={execution.jobExecutionId === selectedId ? 'selected' : undefined}
                  onClick={() => onSelect(execution.jobExecutionId)}
                >
                  <td>{execution.jobExecutionId}</td>
                  <td>{execution.jobName}</td>
                  <td>
                    <StatusBadge status={execution.status} />
                  </td>
                  <td>{formatStartedBy(execution.startedBy)}</td>
                  <td>{formatTime(execution.startTime)}</td>
                  <td>{formatDuration(runtime(execution))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{status}</span>
}

/** Duration of a finished run, or time elapsed so far for a running one. */
export function runtime(execution: Execution): number | null {
  if (execution.durationMs != null) return execution.durationMs
  if (execution.running && execution.startTime) return Date.now() - new Date(execution.startTime).getTime()
  return null
}
