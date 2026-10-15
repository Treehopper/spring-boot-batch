// Turns the execution log files (see logback-spring.xml) into display lines:
// "2026-10-15T06:02:53.080-07:00 INFO  [worker-1] o.s.b.c.s.AbstractStep - msg"  ->  "2026-10-15 15:02:53 INFO  o.s.b.c.s.AbstractStep - msg"
// in the browser's time zone, without milliseconds and thread name. Other lines (stack traces) stay as they are.

const LINE = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})) (\S+\s*)(?:\[[^\]]*\] )?(.*)$/

const pad = (n: number) => String(n).padStart(2, '0')

export function formatLocalTimestamp(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

export function formatLogLine(line: string): string {
  const match = LINE.exec(line)
  if (!match) return line
  const date = new Date(match[1])
  if (Number.isNaN(date.getTime())) return line
  return `${formatLocalTimestamp(date)} ${match[2]}${match[3]}`
}

export function formatLog(text: string): string {
  return text.split('\n').map(formatLogLine).join('\n')
}
