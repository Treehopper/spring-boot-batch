// Backend calls. The console API (/batch-console/api) serves execution history, logs, stop, jobs, run now, cron
// checks and the scheduler info in every PoC. Schedules come from the PoC's scheduler, see scheduleBackends.ts.

export interface StepInfo {
  name: string
  status: string
  readCount: number
  writeCount: number
  skipCount: number
  commitCount: number
  rollbackCount: number
  durationMs: number | null
}

export interface Execution {
  jobExecutionId: number
  jobName: string
  status: string
  exitCode: string
  exitDescription: string
  running: boolean
  startedBy: string | null
  startTime: string | null
  endTime: string | null
  durationMs: number | null
  parameters: Record<string, string>
  steps: StepInfo[]
}

export type CronDialect = 'quartz' | 'jobrunr'

/** GET /batch-console/api/server */
export interface ServerInfo {
  timeZone: string
  scheduler: {
    name: string
    cronDialect: CronDialect
    /** where schedules are read and changed, see scheduleBackends.ts */
    scheduleApi: 'quartz-manager' | 'console'
    links: { label: string; url: string }[]
  }
}

export interface JobInfo {
  name: string
  description: string | null
}

export interface CronPreview {
  valid: boolean
  error: string | null
  nextFireTimes: string[]
}

export async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  if (!response.ok) throw new Error(errorMessage(response, text))
  const type = response.headers.get('Content-Type') ?? ''
  return (type.includes('json') && text ? JSON.parse(text) : text) as T
}

function errorMessage(response: Response, text: string): string {
  try {
    const json = JSON.parse(text)
    return json.message || json.detail || json.error || `${response.status} ${response.statusText}`
  } catch {
    return text || `${response.status} ${response.statusText}`
  }
}

const console_ = '/batch-console/api'

export const batch = {
  executions: (jobName?: string) =>
    request<Execution[]>('GET', `${console_}/executions${jobName ? `?jobName=${encodeURIComponent(jobName)}` : ''}`),
  execution: (id: number) => request<Execution>('GET', `${console_}/executions/${id}`),
  log: (id: number) => request<string>('GET', `${console_}/executions/${id}/log`),
  stop: (id: number) => request<Execution>('POST', `${console_}/executions/${id}/stop`),
  server: () => request<ServerInfo>('GET', `${console_}/server`),
  jobs: () => request<JobInfo[]>('GET', `${console_}/jobs`),
  run: (jobName: string) => request<{ requestId: string }>('POST', `${console_}/jobs/${encodeURIComponent(jobName)}/run`),
  cronPreview: (expression: string, timeZone: string) =>
    request<CronPreview>(
      'GET',
      `${console_}/cron-preview?expression=${encodeURIComponent(expression)}&timeZone=${encodeURIComponent(timeZone)}`,
    ),
}
