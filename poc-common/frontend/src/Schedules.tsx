import { useState } from 'react'
import type { CronDialect } from './api'
import { describe, fromCron } from './cron'
import { browserTimeZone, formatTime } from './format'
import { RecurrenceEditor } from './RecurrenceEditor'
import { isPaused, type Schedule, type ScheduleBackend, type ScheduledJob } from './scheduleBackends'

interface Props {
  jobs: ScheduledJob[] | undefined
  error: string | undefined
  backend: ScheduleBackend
  dialect: CronDialect
  schedulerName: string
  serverTimeZone: string | undefined
  onChanged: () => void
  onStarted: (jobName: string) => void
}

export function Schedules({ jobs, error, backend, dialect, schedulerName, serverTimeZone, onChanged, onStarted }: Props) {
  const [editing, setEditing] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  async function act(action: () => Promise<unknown>, after: () => void = onChanged) {
    setActionError(null)
    try {
      await action()
      after()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e))
    }
  }

  const runNow = (jobName: string) => (
    <button onClick={() => act(() => backend.runNow(jobName), () => onStarted(jobName))}>Run now</button>
  )

  return (
    <section className="card">
      <header className="card-header">
        <h2>Schedules</h2>
        <span className="muted">{schedulerName}: runtime-editable cron schedules, each with its own time zone</span>
      </header>
      {error && <p className="error">Could not load schedules: {error}</p>}
      {actionError && <p className="error">{actionError}</p>}
      {!jobs && !error && <p className="muted">Loading…</p>}
      {jobs && (
        <table>
          <thead>
            <tr>
              <th>Job</th>
              <th>Schedule</th>
              <th>Time zone</th>
              <th>State</th>
              <th>Next run</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => [
              ...job.schedules.map((schedule, index) =>
                editing === schedule.id ? (
                  <tr key={schedule.id}>
                    <td>{index === 0 && <JobName job={job} />}</td>
                    <td colSpan={5}>
                      <RecurrenceEditor
                        schedule={schedule}
                        backend={backend}
                        dialect={dialect}
                        serverTimeZone={serverTimeZone}
                        onCancel={() => setEditing(null)}
                        onSaved={() => {
                          setEditing(null)
                          onChanged()
                        }}
                      />
                    </td>
                  </tr>
                ) : (
                  <tr key={schedule.id}>
                    <td>{index === 0 && <JobName job={job} />}</td>
                    <td>
                      {describe(fromCron(schedule.cronExpression, dialect))}
                      <div className="muted small">
                        <code>{schedule.cronExpression}</code>
                      </div>
                    </td>
                    <td>{schedule.timeZone}</td>
                    <td>
                      <span className={`badge state-${schedule.state.toLowerCase()}`}>{schedule.state}</span>
                    </td>
                    <td>
                      <NextRun schedule={schedule} />
                    </td>
                    <td className="actions">
                      {index === 0 && runNow(job.name)}
                      {isPaused(schedule) ? (
                        <button onClick={() => act(() => backend.resume(schedule))}>Resume</button>
                      ) : (
                        <button onClick={() => act(() => backend.pause(schedule))}>Pause</button>
                      )}
                      <button onClick={() => setEditing(schedule.id)}>Edit</button>
                    </td>
                  </tr>
                ),
              ),
              job.schedules.length === 0 && (
                <tr key={`${job.name}-none`}>
                  <td>
                    <JobName job={job} />
                  </td>
                  <td colSpan={4} className="muted">
                    no schedule{job.pendingRuns > 0 && `, ${job.pendingRuns} one-off run(s) pending`}
                  </td>
                  <td className="actions">{runNow(job.name)}</td>
                </tr>
              ),
            ])}
          </tbody>
        </table>
      )}
    </section>
  )
}

function JobName({ job }: { job: ScheduledJob }) {
  return (
    <>
      <strong>{job.name}</strong>
      {job.description && <div className="muted small">{job.description}</div>}
    </>
  )
}

function NextRun({ schedule }: { schedule: Schedule }) {
  // Quartz keeps a next fire time for paused triggers; it only applies after resuming
  if (!schedule.nextRun || isPaused(schedule)) return <span className="muted">–</span>
  const local = browserTimeZone()
  return (
    <>
      {formatTime(schedule.nextRun, schedule.timeZone)}
      {schedule.timeZone !== local && (
        <div className="muted small">
          {formatTime(schedule.nextRun)} {local}
        </div>
      )}
    </>
  )
}
