// Recurrence builder model: an Outlook-like recurrence <-> cron expression, in the dialect of the PoC's scheduler:
// - quartz (Quartz, db-scheduler): second minute hour day-of-month month day-of-week; day-of-week 1 = SUN ... 7 = SAT,
//   "?" for the day field that isn't used, d#n for the nth weekday, dL for the last weekday of the month
// - jobrunr: same six fields, Unix-style: day-of-week 0 = SUN ... 6 = SAT, no "?", no "#" (only dL)
// The builder works on the Quartz form; JobRunr expressions are translated to and from it.

import type { CronDialect } from './api'

export type RecurrenceKind = 'minutes' | 'hourly' | 'daily' | 'weekdays' | 'weekly' | 'monthly' | 'monthlyWeekday' | 'custom'

export const WEEKDAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const
export type Weekday = (typeof WEEKDAYS)[number]

export const WEEKDAY_NAMES: Record<Weekday, string> = {
  MON: 'Monday',
  TUE: 'Tuesday',
  WED: 'Wednesday',
  THU: 'Thursday',
  FRI: 'Friday',
  SAT: 'Saturday',
  SUN: 'Sunday',
}

/** Quartz numbers days of the week from Sunday = 1. */
const QUARTZ_DAY_NUMBER: Record<Weekday, number> = { SUN: 1, MON: 2, TUE: 3, WED: 4, THU: 5, FRI: 6, SAT: 7 }

export const MINUTE_STEPS = [1, 2, 5, 10, 15, 20, 30]
export const HOUR_STEPS = [1, 2, 3, 4, 6, 8, 12]

export type Nth = 1 | 2 | 3 | 4 | 'L'
export const NTH_NAMES: Record<string, string> = { 1: 'first', 2: 'second', 3: 'third', 4: 'fourth', L: 'last' }

/**
 * All fields of the builder form. Only the ones that belong to `kind` are used; the others keep their values, so
 * switching back and forth between recurrence kinds doesn't lose input.
 */
export interface RecurrenceForm {
  kind: RecurrenceKind
  everyMinutes: number
  everyHours: number
  /** minute past the hour, for hourly */
  minute: number
  /** "HH:MM", for daily, weekdays, weekly and monthly */
  time: string
  days: Weekday[]
  /** day of month 1–31, or 'L' for the last day */
  monthDay: number | 'L'
  nth: Nth
  nthWeekday: Weekday
  /** the raw expression, for custom */
  expression: string
}

export const DEFAULT_FORM: RecurrenceForm = {
  kind: 'daily',
  everyMinutes: 15,
  everyHours: 1,
  minute: 0,
  time: '09:00',
  days: ['MON'],
  monthDay: 1,
  nth: 1,
  nthWeekday: 'MON',
  expression: '0 0 9 * * ?',
}

/** Thrown for a form that doesn't describe a schedule, e.g. weekly without days. */
export class RecurrenceError extends Error {}

function parseTime(time: string): { hour: number; minute: number } {
  const match = /^(\d{1,2}):(\d{2})$/.exec(time)
  if (!match) throw new RecurrenceError('Enter a time')
  const hour = Number(match[1])
  const minute = Number(match[2])
  if (hour > 23 || minute > 59) throw new RecurrenceError('Enter a valid time')
  return { hour, minute }
}

const pad = (n: number) => String(n).padStart(2, '0')

/** The recurrence kinds the dialect can express (JobRunr: "monthly on a weekday" only as the last weekday). */
export function nthOptions(dialect: CronDialect): Nth[] {
  return dialect === 'jobrunr' ? ['L'] : [1, 2, 3, 4, 'L']
}

export function toCron(form: RecurrenceForm, dialect: CronDialect = 'quartz'): string {
  const quartz = toQuartzCron(form)
  if (dialect === 'quartz' || form.kind === 'custom') return quartz
  if (form.kind === 'monthlyWeekday' && form.nth !== 'L')
    throw new RecurrenceError('JobRunr can only schedule the last weekday of a month, not the first to fourth')
  return quartzToJobRunr(quartz)
}

function toQuartzCron(form: RecurrenceForm): string {
  switch (form.kind) {
    case 'minutes':
      return form.everyMinutes === 1 ? '0 * * * * ?' : `0 0/${form.everyMinutes} * * * ?`
    case 'hourly':
      if (form.minute < 0 || form.minute > 59) throw new RecurrenceError('Minute must be between 0 and 59')
      return `0 ${form.minute} ${form.everyHours === 1 ? '*' : `0/${form.everyHours}`} * * ?`
    case 'custom':
      if (!form.expression.trim()) throw new RecurrenceError('Enter a cron expression')
      return form.expression.trim().replace(/\s+/g, ' ')
  }
  const { hour, minute } = parseTime(form.time)
  switch (form.kind) {
    case 'daily':
      return `0 ${minute} ${hour} * * ?`
    case 'weekdays':
      return `0 ${minute} ${hour} ? * MON-FRI`
    case 'weekly': {
      if (form.days.length === 0) throw new RecurrenceError('Choose at least one day')
      const days = WEEKDAYS.filter((day) => form.days.includes(day))
      return `0 ${minute} ${hour} ? * ${days.join(',')}`
    }
    case 'monthly':
      return `0 ${minute} ${hour} ${form.monthDay} * ?`
    case 'monthlyWeekday': {
      const day = QUARTZ_DAY_NUMBER[form.nthWeekday]
      return `0 ${minute} ${hour} ? * ${form.nth === 'L' ? `${day}L` : `${day}#${form.nth}`}`
    }
  }
}

/**
 * Reads an expression back into the form. Expressions in the shapes that `toCron` produces are recognised;
 * everything else becomes `custom`.
 */
export function fromCron(expression: string, dialect: CronDialect = 'quartz'): RecurrenceForm {
  const original = expression.trim().replace(/\s+/g, ' ')
  if (dialect === 'quartz') return fromQuartzCron(original)
  const quartz = jobRunrToQuartz(original)
  const form = quartz ? fromQuartzCron(quartz) : { ...DEFAULT_FORM, kind: 'custom' as const }
  return { ...form, expression: original }
}

/** Quartz-shaped expressions as produced by toQuartzCron -> JobRunr. */
function quartzToJobRunr(quartz: string): string {
  const fields = quartz.split(' ').map((field) => (field === '?' ? '*' : field))
  const lastWeekday = /^([1-7])L$/.exec(fields[5])
  if (lastWeekday) fields[5] = `${Number(lastWeekday[1]) - 1}L`
  return fields.join(' ')
}

/** JobRunr -> Quartz form, for reading back; null if it isn't a six-field expression without "?". */
function jobRunrToQuartz(jobrunr: string): string | null {
  const fields = jobrunr.split(' ')
  if (fields.length !== 6 || fields.includes('?')) return null
  const lastWeekday = /^([0-6])L$/.exec(fields[5])
  if (lastWeekday) fields[5] = `${Number(lastWeekday[1]) + 1}L`
  if (fields[5] === '*') fields[5] = '?'
  else if (fields[3] === '*') fields[3] = '?'
  return fields.join(' ')
}

function fromQuartzCron(cron: string): RecurrenceForm {
  const base = { ...DEFAULT_FORM, expression: cron }
  const time = (hour: string, minute: string) => `${pad(Number(hour))}:${pad(Number(minute))}`
  let m: RegExpExecArray | null

  if (cron === '0 * * * * ?') return { ...base, kind: 'minutes', everyMinutes: 1 }
  if ((m = /^0 0\/(\d+) \* \* \* \?$/.exec(cron)) && MINUTE_STEPS.includes(Number(m[1])))
    return { ...base, kind: 'minutes', everyMinutes: Number(m[1]) }
  if ((m = /^0 (\d{1,2}) (\*|0\/(\d+)) \* \* \?$/.exec(cron)) && Number(m[1]) < 60) {
    const every = m[3] ? Number(m[3]) : 1
    if (HOUR_STEPS.includes(every)) return { ...base, kind: 'hourly', everyHours: every, minute: Number(m[1]) }
  }
  if ((m = /^0 (\d{1,2}) (\d{1,2}) \* \* \?$/.exec(cron)) && validTime(m[2], m[1]))
    return { ...base, kind: 'daily', time: time(m[2], m[1]) }
  if ((m = /^0 (\d{1,2}) (\d{1,2}) \? \* MON-FRI$/.exec(cron)) && validTime(m[2], m[1]))
    return { ...base, kind: 'weekdays', time: time(m[2], m[1]) }
  if ((m = /^0 (\d{1,2}) (\d{1,2}) \? \* ([A-Z]{3}(?:,[A-Z]{3})*)$/.exec(cron)) && validTime(m[2], m[1])) {
    const days = m[3].split(',')
    if (days.every((day): day is Weekday => (WEEKDAYS as readonly string[]).includes(day)))
      return { ...base, kind: 'weekly', time: time(m[2], m[1]), days }
  }
  if ((m = /^0 (\d{1,2}) (\d{1,2}) (\d{1,2}|L) \* \?$/.exec(cron)) && validTime(m[2], m[1])) {
    const day = m[3] === 'L' ? 'L' : Number(m[3])
    if (day === 'L' || (day >= 1 && day <= 31)) return { ...base, kind: 'monthly', time: time(m[2], m[1]), monthDay: day }
  }
  if ((m = /^0 (\d{1,2}) (\d{1,2}) \? \* ([1-7])(?:#([1-4])|(L))$/.exec(cron)) && validTime(m[2], m[1])) {
    const weekday = WEEKDAYS.find((day) => QUARTZ_DAY_NUMBER[day] === Number(m![3]))!
    const nth: Nth = m[5] ? 'L' : (Number(m[4]) as Nth)
    return { ...base, kind: 'monthlyWeekday', time: time(m[2], m[1]), nth, nthWeekday: weekday }
  }
  return { ...base, kind: 'custom' }
}

function validTime(hour: string, minute: string): boolean {
  return Number(hour) <= 23 && Number(minute) <= 59
}

/** "Every weekday at 09:00", "Weekly on Monday, Wednesday at 06:30", ... */
export function describe(form: RecurrenceForm): string {
  switch (form.kind) {
    case 'minutes':
      return form.everyMinutes === 1 ? 'Every minute' : `Every ${form.everyMinutes} minutes`
    case 'hourly':
      return form.everyHours === 1
        ? `Every hour at minute ${form.minute}`
        : `Every ${form.everyHours} hours at minute ${form.minute} (00:${pad(form.minute)}, ${pad(form.everyHours)}:${pad(form.minute)}, …)`
    case 'daily':
      return `Daily at ${form.time}`
    case 'weekdays':
      return `Every weekday (Mon–Fri) at ${form.time}`
    case 'weekly': {
      const days = WEEKDAYS.filter((day) => form.days.includes(day)).map((day) => WEEKDAY_NAMES[day])
      return `Weekly on ${days.join(', ')} at ${form.time}`
    }
    case 'monthly':
      return form.monthDay === 'L'
        ? `Monthly on the last day at ${form.time}`
        : `Monthly on day ${form.monthDay} at ${form.time}${form.monthDay > 28 ? ' (skipped in shorter months)' : ''}`
    case 'monthlyWeekday':
      return `Monthly on the ${NTH_NAMES[form.nth]} ${WEEKDAY_NAMES[form.nthWeekday]} at ${form.time}`
    case 'custom':
      return 'Custom cron expression'
  }
}
