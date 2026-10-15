import { useState } from 'react'
import { quartz, type Key, type ScheduledJob, type Trigger } from './api'
import { describe, fromCron } from './cron'
import { browserTimeZone, formatTime } from './format'
import { RecurrenceEditor } from './RecurrenceEditor'

export interface ScheduleRow {
  job: ScheduledJob
  triggers: Trigger[]
}

/** Jobs with their triggers, from the Quartz Manager API. */
export async function loadSchedules(): Promise<ScheduleRow[]> {
  const jobs = await quartz.jobs()
  const rows = await Promise.all(
    jobs.map(async (job) => ({
      job,
      // one-off triggers can disappear between the two calls
      triggers: (await Promise.all(job.triggerKeys.map((key) => quartz.trigger(key).catch(() => null)))).filter(
        (t): t is Trigger => t !== null,
      ),
    })),
  )
  return rows.sort((a, b) => a.job.jobKeyDTO.name.localeCompare(b.job.jobKeyDTO.name))
}

interface Props {
  rows: ScheduleRow[] | undefined
  error: string | undefined
  serverTimeZone: string | undefined
  onChanged: () => void
  onStarted: (job: Key) => void
}

export function Schedules({ rows, error, serverTimeZone, onChanged, onStarted }: Props) {
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

  return (
    <section className="card">
      <header className="card-header">
        <h2>Schedules</h2>
        <span className="muted">Quartz triggers, read and changed through the Quartz Manager API</span>
      </header>
      {error && <p className="error">Could not load schedules: {error}</p>}
      {actionError && <p className="error">{actionError}</p>}
      {!rows && !error && <p className="muted">Loading…</p>}
      {rows && (
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
            {rows.map(({ job, triggers }) => {
              const cronTriggers = triggers.filter((t) => t.cronExpression)
              const pending = triggers.length - cronTriggers.length
              return [
                ...cronTriggers.map((trigger, index) => {
                  const id = `${trigger.triggerKeyDTO.group}.${trigger.triggerKeyDTO.name}`
                  return editing === id ? (
                    <tr key={id}>
                      <td>{index === 0 && <JobName job={job} />}</td>
                      <td colSpan={5}>
                        <RecurrenceEditor
                          trigger={trigger}
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
                    <tr key={id}>
                      <td>{index === 0 && <JobName job={job} />}</td>
                      <td>
                        {describe(fromCron(trigger.cronExpression!))}
                        <div className="muted small">
                          <code>{trigger.cronExpression}</code>
                        </div>
                      </td>
                      <td>{trigger.timeZone}</td>
                      <td>
                        <span className={`badge state-${trigger.state.toLowerCase()}`}>{trigger.state}</span>
                      </td>
                      <td>
                        <NextRun trigger={trigger} />
                      </td>
                      <td className="actions">
                        {index === 0 && (
                          <button onClick={() => act(() => quartz.runNow(job.jobKeyDTO), () => onStarted(job.jobKeyDTO))}>
                            Run now
                          </button>
                        )}
                        {trigger.state === 'PAUSED' ? (
                          <button onClick={() => act(() => quartz.resume(trigger.triggerKeyDTO))}>Resume</button>
                        ) : (
                          <button onClick={() => act(() => quartz.pause(trigger.triggerKeyDTO))}>Pause</button>
                        )}
                        <button onClick={() => setEditing(id)}>Edit</button>
                      </td>
                    </tr>
                  )
                }),
                cronTriggers.length === 0 && (
                  <tr key={`${job.jobKeyDTO.name}-none`}>
                    <td>
                      <JobName job={job} />
                    </td>
                    <td colSpan={4} className="muted">
                      no schedule{pending > 0 && `, ${pending} one-off run(s) pending`}
                    </td>
                    <td className="actions">
                      <button onClick={() => act(() => quartz.runNow(job.jobKeyDTO), () => onStarted(job.jobKeyDTO))}>
                        Run now
                      </button>
                    </td>
                  </tr>
                ),
              ]
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}

function JobName({ job }: { job: ScheduledJob }) {
  return (
    <>
      <strong>{job.jobKeyDTO.name}</strong>
      {job.description && <div className="muted small">{job.description}</div>}
    </>
  )
}

function NextRun({ trigger }: { trigger: Trigger }) {
  if (!trigger.nextFireTime) return <span className="muted">–</span>
  const local = browserTimeZone()
  return (
    <>
      {formatTime(trigger.nextFireTime, trigger.timeZone ?? undefined)}
      {trigger.timeZone && trigger.timeZone !== local && (
        <div className="muted small">
          {formatTime(trigger.nextFireTime)} {local}
        </div>
      )}
    </>
  )
}
