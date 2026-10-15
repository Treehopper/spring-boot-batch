import { useEffect, useRef, useState } from 'react'
import { batch } from './api'
import { runtime, StatusBadge } from './Executions'
import { formatDuration, formatStartedBy, formatTime } from './format'
import { usePolling } from './usePolling'

const RUNNING_REFRESH_MS = 2000

export function ExecutionDetail({ id, onClose, onChanged }: { id: number; onClose: () => void; onChanged: () => void }) {
  const [running, setRunning] = useState(true)
  const execution = usePolling(() => batch.execution(id), running ? RUNNING_REFRESH_MS : null, [id])
  const log = usePolling(() => batch.log(id), running ? RUNNING_REFRESH_MS : null, [id])
  const [stopError, setStopError] = useState<string | null>(null)

  const isRunning = execution.data?.running ?? true
  useEffect(() => {
    // One last refresh of the log when the run has just finished, then stop polling
    if (running && !isRunning) log.reload()
    setRunning(isRunning)
  }, [isRunning])

  async function stop() {
    setStopError(null)
    try {
      await batch.stop(id)
      execution.reload()
      onChanged()
    } catch (e) {
      setStopError(e instanceof Error ? e.message : String(e))
    }
  }

  const e = execution.data
  return (
    <section className="card detail">
      <header className="card-header">
        <h2>Execution #{id}</h2>
        {e && <StatusBadge status={e.status} />}
        <span className="spacer" />
        {e?.running && (
          <button className="danger" onClick={stop} disabled={e.status === 'STOPPING'}>
            Stop
          </button>
        )}
        <button onClick={onClose} aria-label="Close">
          Close
        </button>
      </header>
      {execution.error && <p className="error">{execution.error}</p>}
      {stopError && <p className="error">{stopError}</p>}
      {e && (
        <>
          <dl className="facts">
            <dt>Job</dt>
            <dd>{e.jobName}</dd>
            <dt>Started by</dt>
            <dd>{formatStartedBy(e.startedBy)}</dd>
            <dt>Start</dt>
            <dd>{formatTime(e.startTime)}</dd>
            <dt>End</dt>
            <dd>{formatTime(e.endTime)}</dd>
            <dt>Duration</dt>
            <dd>{formatDuration(runtime(e))}</dd>
            <dt>Exit code</dt>
            <dd>{e.exitCode}</dd>
            <dt>Parameters</dt>
            <dd>
              {Object.entries(e.parameters)
                .filter(([name]) => name !== 'startedBy')
                .map(([name, value]) => (
                  <code key={name} className="param">
                    {name}={value}
                  </code>
                ))}
            </dd>
          </dl>

          <h3>Steps</h3>
          {e.steps.length === 0 ? (
            <p className="muted">No steps yet.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Step</th>
                  <th>Status</th>
                  <th className="num">Read</th>
                  <th className="num">Written</th>
                  <th className="num">Skipped</th>
                  <th className="num">Commits</th>
                  <th className="num">Rollbacks</th>
                  <th>Duration</th>
                </tr>
              </thead>
              <tbody>
                {e.steps.map((step) => (
                  <tr key={step.name}>
                    <td>{step.name}</td>
                    <td>
                      <StatusBadge status={step.status} />
                    </td>
                    <td className="num">{step.readCount}</td>
                    <td className="num">{step.writeCount}</td>
                    <td className="num">{step.skipCount}</td>
                    <td className="num">{step.commitCount}</td>
                    <td className="num">{step.rollbackCount}</td>
                    <td>{formatDuration(step.durationMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {e.exitDescription && (
            <>
              <h3>Exit description</h3>
              <pre className="log">{e.exitDescription}</pre>
            </>
          )}
        </>
      )}

      <h3>Log</h3>
      {log.error && <p className="error">{log.error}</p>}
      <LogView text={log.data} />
    </section>
  )
}

function LogView({ text }: { text: string | undefined }) {
  const ref = useRef<HTMLPreElement>(null)
  useEffect(() => {
    const element = ref.current
    if (element) element.scrollTop = element.scrollHeight
  }, [text])
  if (text === undefined) return <p className="muted">Loading…</p>
  if (text === '') return <p className="muted">No log lines for this execution.</p>
  return (
    <pre ref={ref} className="log">
      {text}
    </pre>
  )
}
