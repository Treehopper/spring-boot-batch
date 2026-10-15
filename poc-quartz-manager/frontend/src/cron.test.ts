import { describe as group, expect, it } from 'vitest'
import { DEFAULT_FORM, describe, fromCron, toCron, type RecurrenceForm } from './cron'

const form = (overrides: Partial<RecurrenceForm>): RecurrenceForm => ({ ...DEFAULT_FORM, ...overrides })

group('toCron', () => {
  it.each([
    [{ kind: 'minutes', everyMinutes: 1 }, '0 * * * * ?'],
    [{ kind: 'minutes', everyMinutes: 15 }, '0 0/15 * * * ?'],
    [{ kind: 'hourly', everyHours: 1, minute: 30 }, '0 30 * * * ?'],
    [{ kind: 'hourly', everyHours: 6, minute: 5 }, '0 5 0/6 * * ?'],
    [{ kind: 'daily', time: '02:30' }, '0 30 2 * * ?'],
    [{ kind: 'weekdays', time: '18:05' }, '0 5 18 ? * MON-FRI'],
    [{ kind: 'weekly', time: '07:00', days: ['FRI', 'MON', 'WED'] }, '0 0 7 ? * MON,WED,FRI'],
    [{ kind: 'monthly', time: '00:00', monthDay: 15 }, '0 0 0 15 * ?'],
    [{ kind: 'monthly', time: '23:59', monthDay: 'L' }, '0 59 23 L * ?'],
    [{ kind: 'monthlyWeekday', time: '09:00', nth: 2, nthWeekday: 'TUE' }, '0 0 9 ? * 3#2'],
    [{ kind: 'monthlyWeekday', time: '09:00', nth: 'L', nthWeekday: 'FRI' }, '0 0 9 ? * 6L'],
    [{ kind: 'monthlyWeekday', time: '09:00', nth: 1, nthWeekday: 'SUN' }, '0 0 9 ? * 1#1'],
    [{ kind: 'custom', expression: '  0 15 10 ? *  6#3 ' }, '0 15 10 ? * 6#3'],
  ] as [Partial<RecurrenceForm>, string][])('%o -> %s', (overrides, expected) => {
    expect(toCron(form(overrides))).toBe(expected)
  })

  it('rejects incomplete forms', () => {
    expect(() => toCron(form({ kind: 'weekly', days: [] }))).toThrow('Choose at least one day')
    expect(() => toCron(form({ kind: 'daily', time: '' }))).toThrow('Enter a time')
    expect(() => toCron(form({ kind: 'daily', time: '24:00' }))).toThrow('Enter a valid time')
    expect(() => toCron(form({ kind: 'custom', expression: ' ' }))).toThrow('Enter a cron expression')
  })
})

group('fromCron', () => {
  it('reads back every shape toCron produces', () => {
    const forms: Partial<RecurrenceForm>[] = [
      { kind: 'minutes', everyMinutes: 1 },
      { kind: 'minutes', everyMinutes: 2 },
      { kind: 'hourly', everyHours: 1, minute: 30 },
      { kind: 'hourly', everyHours: 12, minute: 0 },
      { kind: 'daily', time: '02:30' },
      { kind: 'weekdays', time: '18:05' },
      { kind: 'weekly', time: '07:00', days: ['MON', 'SUN'] },
      { kind: 'monthly', time: '06:00', monthDay: 31 },
      { kind: 'monthly', time: '06:00', monthDay: 'L' },
      { kind: 'monthlyWeekday', time: '09:00', nth: 4, nthWeekday: 'SAT' },
      { kind: 'monthlyWeekday', time: '09:00', nth: 'L', nthWeekday: 'MON' },
    ]
    for (const overrides of forms) {
      const cron = toCron(form(overrides))
      const parsed = fromCron(cron)
      expect(parsed).toMatchObject(overrides)
      expect(toCron(parsed)).toBe(cron)
    }
  })

  it('reads the seeded schedules', () => {
    expect(fromCron('0 0/2 * * * ?')).toMatchObject({ kind: 'minutes', everyMinutes: 2 })
    expect(fromCron('0 30 2 * * ?')).toMatchObject({ kind: 'daily', time: '02:30' })
  })

  it('falls back to custom for anything else', () => {
    for (const cron of ['0 0/7 * * * ?', '0 0 0/5 * * ?', '30 0 9 * * ?', '0 0 9 1,15 * ?', '0 0 9 ? * MON-WED', '0 0 9 * * ? 2030', '0 0 25 * * ?'])
      expect(fromCron(cron)).toMatchObject({ kind: 'custom', expression: cron })
  })
})

group('describe', () => {
  it('describes recurrences in words', () => {
    expect(describe(fromCron('0 0/2 * * * ?'))).toBe('Every 2 minutes')
    expect(describe(fromCron('0 15 0/6 * * ?'))).toBe('Every 6 hours at minute 15 (00:15, 06:15, …)')
    expect(describe(fromCron('0 30 2 * * ?'))).toBe('Daily at 02:30')
    expect(describe(fromCron('0 0 9 ? * MON-FRI'))).toBe('Every weekday (Mon–Fri) at 09:00')
    expect(describe(fromCron('0 0 7 ? * MON,FRI'))).toBe('Weekly on Monday, Friday at 07:00')
    expect(describe(fromCron('0 0 6 31 * ?'))).toBe('Monthly on day 31 at 06:00 (skipped in shorter months)')
    expect(describe(fromCron('0 0 9 ? * 6L'))).toBe('Monthly on the last Friday at 09:00')
    expect(describe(fromCron('0 0 9 1,15 * ?'))).toBe('Custom cron expression')
  })
})
