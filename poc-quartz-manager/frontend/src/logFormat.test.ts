import { expect, it } from 'vitest'
import { formatLocalTimestamp, formatLog, formatLogLine } from './logFormat'

const local = (iso: string) => formatLocalTimestamp(new Date(iso))

it('shows the time in the browser time zone, without milliseconds, offset and thread', () => {
  const line = '2026-10-15T06:02:53.080-07:00 INFO  [batch-scheduler_Worker-1] o.s.batch.core.step.AbstractStep - Executing step'
  expect(formatLogLine(line)).toBe(`${local('2026-10-15T13:02:53Z')} INFO  o.s.batch.core.step.AbstractStep - Executing step`)
})

it('handles the current format without thread name and other offsets', () => {
  expect(formatLogLine('2026-10-15T15:02:53.999+02:00 ERROR c.e.p.n.JobNotificationListener - Could not send')).toBe(
    `${local('2026-10-15T13:02:53Z')} ERROR c.e.p.n.JobNotificationListener - Could not send`,
  )
  expect(formatLogLine('2026-10-15T13:02:53.5Z WARN  x - y')).toBe(`${local('2026-10-15T13:02:53Z')} WARN  x - y`)
})

it('formats as yyyy-MM-dd HH:mm:ss', () => {
  expect(local('2026-10-15T13:02:53Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
})

it('keeps stack traces and other lines unchanged', () => {
  const text = [
    '2026-10-15T06:02:53.080-07:00 ERROR [w] a.B - boom',
    'java.lang.IllegalStateException: Simulated failure',
    '\tat com.example.Foo.bar(Foo.java:42)',
    '',
  ].join('\n')
  const lines = formatLog(text).split('\n')
  expect(lines[0]).toBe(`${local('2026-10-15T13:02:53Z')} ERROR a.B - boom`)
  expect(lines.slice(1)).toEqual(['java.lang.IllegalStateException: Simulated failure', '\tat com.example.Foo.bar(Foo.java:42)', ''])
})
