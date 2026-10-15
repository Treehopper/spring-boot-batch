// Backend calls. Schedules go straight to the Quartz Manager API (/quartz-manager), execution history, logs and
// stop go to the Spring Batch based console API (/batch-console/api).

export interface Key {
  name: string
  group: string
}

/** GET /quartz-manager/jobs */
export interface ScheduledJob {
  jobKeyDTO: Key
  description: string | null
  triggerKeys: Key[]
}

/** GET /quartz-manager/triggers/{group}/{name} (only the fields the console uses) */
export interface Trigger {
  triggerKeyDTO: Key
  jobKeyDTO: Key
  type: string // e.g. CronTriggerImpl, SimpleTriggerImpl
  state: string // NORMAL, PAUSED, BLOCKED, ...
  cronExpression: string | null
  timeZone: string | null
  nextFireTime: string | null
  previousFireTime: string | null
}

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

export interface CronPreview {
  valid: boolean
  error: string | null
  nextFireTimes: string[]
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
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

const qm = '/quartz-manager'
const path = (key: Key) => `${encodeURIComponent(key.group)}/${encodeURIComponent(key.name)}`

export const quartz = {
  jobs: () => request<ScheduledJob[]>('GET', `${qm}/jobs`),
  trigger: (key: Key) => request<Trigger>('GET', `${qm}/triggers/${path(key)}`),
  runNow: (job: Key) => request<void>('POST', `${qm}/jobs/${path(job)}/trigger`),
  pause: (key: Key) => request<void>('POST', `${qm}/triggers/${path(key)}/pause`),
  resume: (key: Key) => request<void>('POST', `${qm}/triggers/${path(key)}/resume`),
  /** Replaces the trigger's schedule. Quartz Manager rebuilds the trigger, which also un-pauses it. */
  reschedule: (trigger: Trigger, cronExpression: string, timeZone: string) =>
    request<Trigger>('PUT', `${qm}/triggers/${path(trigger.triggerKeyDTO)}`, {
      triggerType: 'CRON',
      jobKey: trigger.jobKeyDTO,
      cronExpression,
      timeZone,
      misfireInstruction: 'DO_NOTHING',
    }),
}

const console_ = '/batch-console/api'

export const batch = {
  executions: (jobName?: string) =>
    request<Execution[]>('GET', `${console_}/executions${jobName ? `?jobName=${encodeURIComponent(jobName)}` : ''}`),
  execution: (id: number) => request<Execution>('GET', `${console_}/executions/${id}`),
  log: (id: number) => request<string>('GET', `${console_}/executions/${id}/log`),
  stop: (id: number) => request<Execution>('POST', `${console_}/executions/${id}/stop`),
  cronPreview: (expression: string, timeZone: string) =>
    request<CronPreview>(
      'GET',
      `${console_}/cron-preview?expression=${encodeURIComponent(expression)}&timeZone=${encodeURIComponent(timeZone)}`,
    ),
}
