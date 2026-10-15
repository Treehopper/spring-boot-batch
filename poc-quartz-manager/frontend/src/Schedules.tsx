import { useEffect, useMemo, useState } from 'react'
import { batch, quartz, type CronPreview, type Key, type ScheduledJob, type Trigger } from './api'
import { browserTimeZone, formatTime } from './format'

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
  onChanged: () => void
  onStarted: (job: Key) => void
}

export function Schedules({ rows, error, onChanged, onStarted }: Props) {
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
                        <CronEditor
                          trigger={trigger}
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
                        <code>{trigger.cronExpression}</code>
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

function CronEditor({ trigger, onCancel, onSaved }: { trigger: Trigger; onCancel: () => void; onSaved: () => void }) {
  const [cron, setCron] = useState(trigger.cronExpression ?? '')
  const [timeZone, setTimeZone] = useState(trigger.timeZone ?? browserTimeZone())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const timeZones = useMemo(() => Intl.supportedValuesOf('timeZone'), [])
  const [preview, setPreview] = useState<CronPreview | null>(null)

  // Check the expression and time zone while typing (Quartz Manager only answers HTTP 500 for invalid input)
  useEffect(() => {
    setPreview(null)
    if (!cron.trim() || !timeZone) return
    let cancelled = false
    const timer = setTimeout(() => {
      batch
        .cronPreview(cron.trim(), timeZone)
        .then((result) => !cancelled && setPreview(result))
        .catch((e) => !cancelled && setPreview({ valid: false, error: String(e), nextFireTimes: [] }))
    }, 300)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [cron, timeZone])

  async function save(event: React.FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await quartz.reschedule(trigger, cron.trim(), timeZone)
      // Rescheduling replaces the trigger, which un-pauses it: keep a paused schedule paused
      if (trigger.state === 'PAUSED') await quartz.pause(trigger.triggerKeyDTO)
      onSaved()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="cron-editor" onSubmit={save}>
      <label>
        Cron expression
        <input value={cron} onChange={(e) => setCron(e.target.value)} required spellCheck={false} autoFocus />
        <span className="muted small">Quartz format: second minute hour day-of-month month day-of-week, e.g. 0 30 2 * * ?</span>
      </label>
      <label>
        Time zone
        <input list="time-zones" value={timeZone} onChange={(e) => setTimeZone(e.target.value)} required />
        <datalist id="time-zones">
          {timeZones.map((zone) => (
            <option key={zone} value={zone} />
          ))}
        </datalist>
      </label>
      <div className="actions">
        <button type="submit" className="primary" disabled={saving || !preview?.valid}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button type="button" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
      </div>
      {preview && !preview.valid && <p className="error">{preview.error}</p>}
      {preview?.valid && (
        <p className="preview muted small">
          Next runs: {preview.nextFireTimes.slice(0, 3).map((time) => formatTime(time, timeZone)).join(' · ')} ({timeZone})
        </p>
      )}
      {error && <p className="error">{error}</p>}
    </form>
  )
}
