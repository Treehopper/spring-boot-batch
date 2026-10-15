import { useEffect, useMemo, useState } from 'react'
import { batch, quartz, type CronPreview, type Trigger } from './api'
import {
  describe,
  fromCron,
  HOUR_STEPS,
  MINUTE_STEPS,
  NTH_NAMES,
  RecurrenceError,
  toCron,
  WEEKDAY_NAMES,
  WEEKDAYS,
  type Nth,
  type RecurrenceForm,
  type RecurrenceKind,
  type Weekday,
} from './cron'
import { browserTimeZone, formatTime } from './format'

const KINDS: [RecurrenceKind, string][] = [
  ['minutes', 'Every few minutes'],
  ['hourly', 'Hourly'],
  ['daily', 'Daily'],
  ['weekdays', 'Every weekday (Mon–Fri)'],
  ['weekly', 'Weekly'],
  ['monthly', 'Monthly, on a day of the month'],
  ['monthlyWeekday', 'Monthly, on a weekday'],
  ['custom', 'Custom cron expression'],
]

const MONTH_DAYS: (number | 'L')[] = [...Array.from({ length: 31 }, (_, i) => i + 1), 'L']

interface Props {
  trigger: Trigger
  serverTimeZone: string | undefined
  onCancel: () => void
  onSaved: () => void
}

/**
 * Edits a cron schedule as an Outlook-like recurrence. Times are entered in the schedule's time zone, which Quartz
 * uses to evaluate the expression on the server (default: the browser's time zone).
 */
export function RecurrenceEditor({ trigger, serverTimeZone, onCancel, onSaved }: Props) {
  const localZone = browserTimeZone()
  const [form, setForm] = useState<RecurrenceForm>(() => fromCron(trigger.cronExpression ?? ''))
  const [timeZone, setTimeZone] = useState(trigger.timeZone ?? localZone)
  const [preview, setPreview] = useState<CronPreview | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const timeZones = useMemo(() => Intl.supportedValuesOf('timeZone'), [])

  const update = (changes: Partial<RecurrenceForm>) => setForm((current) => ({ ...current, ...changes }))

  let cron: string | null = null
  let formError: string | null = null
  try {
    cron = toCron(form)
  } catch (e) {
    if (!(e instanceof RecurrenceError)) throw e
    formError = e.message
  }

  // Let Quartz check the expression and compute the next runs (Quartz Manager only answers HTTP 500 when invalid)
  useEffect(() => {
    setPreview(null)
    if (!cron || !timeZone) return
    let cancelled = false
    const timer = setTimeout(() => {
      batch
        .cronPreview(cron, timeZone)
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
    if (!cron) return
    setSaving(true)
    setSaveError(null)
    try {
      await quartz.reschedule(trigger, cron, timeZone)
      // Rescheduling replaces the trigger, which un-pauses it: keep a paused schedule paused
      if (trigger.state === 'PAUSED') await quartz.pause(trigger.triggerKeyDTO)
      onSaved()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const usesTime = !['minutes', 'hourly', 'custom'].includes(form.kind)
  const previewZones = [...new Set([timeZone, localZone, serverTimeZone].filter((zone): zone is string => !!zone))]
  const zoneRoles = (zone: string) =>
    [zone === timeZone && 'schedule', zone === localZone && 'you', zone === serverTimeZone && 'server'].filter(
      (role): role is string => !!role,
    )

  return (
    <form className="recurrence-editor" onSubmit={save}>
      <div className="fields">
        <label htmlFor="recurrence-kind">Repeat</label>
        <select id="recurrence-kind" value={form.kind} onChange={(e) => update({ kind: e.target.value as RecurrenceKind })}>
          {KINDS.map(([kind, label]) => (
            <option key={kind} value={kind}>
              {label}
            </option>
          ))}
        </select>

        {form.kind === 'minutes' && (
          <>
            <label htmlFor="every-minutes">Every</label>
            <span className="inline">
              <select id="every-minutes" value={form.everyMinutes} onChange={(e) => update({ everyMinutes: Number(e.target.value) })}>
                {MINUTE_STEPS.map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
              minute(s), from the full hour
            </span>
          </>
        )}

        {form.kind === 'hourly' && (
          <>
            <label htmlFor="every-hours">Every</label>
            <span className="inline">
              <select id="every-hours" value={form.everyHours} onChange={(e) => update({ everyHours: Number(e.target.value) })}>
                {HOUR_STEPS.map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
              hour(s), at minute
              <input
                type="number"
                min={0}
                max={59}
                value={form.minute}
                onChange={(e) => update({ minute: Number(e.target.value) })}
                aria-label="Minute past the hour"
              />
            </span>
          </>
        )}

        {form.kind === 'weekly' && (
          <>
            <span className="label">On</span>
            <span className="days" role="group" aria-label="Days of the week">
              {WEEKDAYS.map((day) => (
                <button
                  key={day}
                  type="button"
                  className={form.days.includes(day) ? 'day on' : 'day'}
                  aria-pressed={form.days.includes(day)}
                  title={WEEKDAY_NAMES[day]}
                  onClick={() =>
                    // based on the latest state, so quick consecutive clicks don't overwrite each other
                    setForm((current) => ({
                      ...current,
                      days: current.days.includes(day) ? current.days.filter((d) => d !== day) : [...current.days, day],
                    }))
                  }
                >
                  {WEEKDAY_NAMES[day].slice(0, 3)}
                </button>
              ))}
            </span>
          </>
        )}

        {form.kind === 'monthly' && (
          <>
            <label htmlFor="month-day">On day</label>
            <select
              id="month-day"
              value={form.monthDay}
              onChange={(e) => update({ monthDay: e.target.value === 'L' ? 'L' : Number(e.target.value) })}
            >
              {MONTH_DAYS.map((day) => (
                <option key={day} value={day}>
                  {day === 'L' ? 'Last day of the month' : day}
                </option>
              ))}
            </select>
          </>
        )}

        {form.kind === 'monthlyWeekday' && (
          <>
            <label htmlFor="nth">On the</label>
            <span className="inline">
              <select
                id="nth"
                value={form.nth}
                onChange={(e) => update({ nth: (e.target.value === 'L' ? 'L' : Number(e.target.value)) as Nth })}
              >
                {(['1', '2', '3', '4', 'L'] as const).map((nth) => (
                  <option key={nth} value={nth}>
                    {NTH_NAMES[nth]}
                  </option>
                ))}
              </select>
              <select value={form.nthWeekday} onChange={(e) => update({ nthWeekday: e.target.value as Weekday })} aria-label="Weekday">
                {WEEKDAYS.map((day) => (
                  <option key={day} value={day}>
                    {WEEKDAY_NAMES[day]}
                  </option>
                ))}
              </select>
            </span>
          </>
        )}

        {usesTime && (
          <>
            <label htmlFor="time">At</label>
            <input id="time" type="time" value={form.time} onChange={(e) => update({ time: e.target.value })} required />
          </>
        )}

        {form.kind === 'custom' && (
          <>
            <label htmlFor="expression">Cron expression</label>
            <span className="stacked">
              <input
                id="expression"
                className="wide"
                value={form.expression}
                onChange={(e) => update({ expression: e.target.value })}
                spellCheck={false}
              />
              <span className="muted small">Quartz format: second minute hour day-of-month month day-of-week [year]</span>
            </span>
          </>
        )}

        <label htmlFor="time-zone">Time zone</label>
        <span className="stacked">
          <span className="inline">
            <input
              id="time-zone"
              className="wide"
              list="time-zones"
              value={timeZone}
              onChange={(e) => setTimeZone(e.target.value)}
              required
            />
            {timeZone !== localZone && (
              <button type="button" onClick={() => setTimeZone(localZone)}>
                Use mine ({localZone})
              </button>
            )}
          </span>
          <span className="muted small">
            Times above are in this zone; Quartz evaluates the schedule in it on the server, including daylight saving time.
          </span>
          <datalist id="time-zones">
            {timeZones.map((zone) => (
              <option key={zone} value={zone} />
            ))}
          </datalist>
        </span>
      </div>

      <div className="summary">
        {formError ? (
          <p className="error">{formError}</p>
        ) : (
          <p>
            <strong>{describe(form)}</strong>
            {form.kind !== 'custom' && (
              <>
                {' '}
                <span className="muted">
                  · cron <code>{cron}</code>{' '}
                  <button type="button" className="link" onClick={() => update({ kind: 'custom', expression: cron ?? '' })}>
                    edit as cron
                  </button>
                </span>
              </>
            )}
          </p>
        )}
        {preview && !preview.valid && <p className="error">{preview.error}</p>}
        {preview?.valid && (
          <table className="preview">
            <thead>
              <tr>
                <th>Next runs</th>
                {previewZones.map((zone) => (
                  <th key={zone}>
                    {zone}
                    <span className="muted small"> ({zoneRoles(zone).join(', ')})</span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {preview.nextFireTimes.slice(0, 3).map((time, index) => (
                <tr key={time}>
                  <td className="muted">{index + 1}</td>
                  {previewZones.map((zone) => (
                    <td key={zone}>{formatTime(time, zone)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="actions">
        <button type="submit" className="primary" disabled={saving || !cron || !preview?.valid}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button type="button" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
      </div>
      {saveError && <p className="error">{saveError}</p>}
    </form>
  )
}
