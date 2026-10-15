// Schedules, from whichever scheduler the PoC uses. The console only sees Schedule / ScheduledJob:
// - quartz-manager: the Quartz PoC; read and changed straight through the Quartz Manager API (/quartz-manager)
// - console: db-scheduler and JobRunr PoCs; the neutral schedules API of the console backend (/batch-console/api)

import { batch, request, type JobInfo } from './api'

export interface Schedule {
  id: string
  jobName: string
  cronExpression: string
  timeZone: string
  /** Quartz: NORMAL, PAUSED, BLOCKED, ...; console API: ACTIVE, PAUSED, RUNNING */
  state: string
  nextRun: string | null
}

export interface ScheduledJob extends JobInfo {
  schedules: Schedule[]
  /** one-off runs waiting to start (Quartz: one-off triggers) */
  pendingRuns: number
}

export interface ScheduleBackend {
  load(): Promise<ScheduledJob[]>
  /** Replaces cron expression and time zone; a paused schedule stays paused. */
  reschedule(schedule: Schedule, cronExpression: string, timeZone: string): Promise<void>
  pause(schedule: Schedule): Promise<void>
  resume(schedule: Schedule): Promise<void>
  runNow(jobName: string): Promise<void>
}

export function isPaused(schedule: Schedule): boolean {
  return schedule.state === 'PAUSED'
}

export function scheduleBackend(scheduleApi: 'quartz-manager' | 'console'): ScheduleBackend {
  return scheduleApi === 'quartz-manager' ? quartzManagerBackend : consoleBackend
}

// --- Quartz Manager API -----------------------------------------------------------------------------------------

interface Key {
  name: string
  group: string
}

/** GET /quartz-manager/jobs (only the fields used here) */
interface QuartzJob {
  jobKeyDTO: Key
  description: string | null
  triggerKeys: Key[]
}

/** GET /quartz-manager/triggers/{group}/{name} (only the fields used here) */
interface QuartzTrigger {
  triggerKeyDTO: Key
  jobKeyDTO: Key
  state: string
  cronExpression: string | null
  timeZone: string | null
  nextFireTime: string | null
}

const qm = '/quartz-manager'
const keyPath = (key: Key) => `${encodeURIComponent(key.group)}/${encodeURIComponent(key.name)}`
/** Quartz jobs and triggers of the PoC live in this group; the job name is the batch job name. */
const GROUP = 'batch'
const triggerKey = (schedule: Schedule): Key => {
  const dot = schedule.id.indexOf('.')
  return { group: schedule.id.slice(0, dot), name: schedule.id.slice(dot + 1) }
}

const quartzManagerBackend: ScheduleBackend = {
  async load() {
    const jobs = await request<QuartzJob[]>('GET', `${qm}/jobs`)
    const result = await Promise.all(
      jobs.map(async (job) => {
        // one-off triggers can disappear between the two calls
        const triggers = (
          await Promise.all(
            job.triggerKeys.map((key) => request<QuartzTrigger>('GET', `${qm}/triggers/${keyPath(key)}`).catch(() => null)),
          )
        ).filter((t): t is QuartzTrigger => t !== null)
        const cron = triggers.filter((t) => t.cronExpression)
        return {
          name: job.jobKeyDTO.name,
          description: job.description,
          pendingRuns: triggers.length - cron.length,
          schedules: cron.map((t) => ({
            id: `${t.triggerKeyDTO.group}.${t.triggerKeyDTO.name}`,
            jobName: job.jobKeyDTO.name,
            cronExpression: t.cronExpression!,
            timeZone: t.timeZone ?? 'UTC',
            state: t.state,
            nextRun: t.nextFireTime,
          })),
        }
      }),
    )
    return result.sort((a, b) => a.name.localeCompare(b.name))
  },

  async reschedule(schedule, cronExpression, timeZone) {
    const key = triggerKey(schedule)
    await request('PUT', `${qm}/triggers/${keyPath(key)}`, {
      triggerType: 'CRON',
      jobKey: { group: GROUP, name: schedule.jobName },
      cronExpression,
      timeZone,
      misfireInstruction: 'DO_NOTHING',
    })
    // Quartz Manager rebuilds the trigger, which un-pauses it: keep a paused schedule paused
    if (isPaused(schedule)) await request('POST', `${qm}/triggers/${keyPath(key)}/pause`)
  },

  async pause(schedule) {
    await request('POST', `${qm}/triggers/${keyPath(triggerKey(schedule))}/pause`)
  },

  async resume(schedule) {
    await request('POST', `${qm}/triggers/${keyPath(triggerKey(schedule))}/resume`)
  },

  async runNow(jobName) {
    await request('POST', `${qm}/jobs/${keyPath({ group: GROUP, name: jobName })}/trigger`)
  },
}

// --- neutral console API ----------------------------------------------------------------------------------------

const schedulesApi = '/batch-console/api/schedules'
const idPath = (schedule: Schedule) => `${schedulesApi}/${encodeURIComponent(schedule.id)}`

const consoleBackend: ScheduleBackend = {
  async load() {
    const [jobs, schedules] = await Promise.all([batch.jobs(), request<Schedule[]>('GET', schedulesApi)])
    return jobs.map((job) => ({
      ...job,
      pendingRuns: 0,
      schedules: schedules.filter((schedule) => schedule.jobName === job.name),
    }))
  },

  async reschedule(schedule, cronExpression, timeZone) {
    await request('PUT', idPath(schedule), { cronExpression, timeZone })
  },

  async pause(schedule) {
    await request('POST', `${idPath(schedule)}/pause`)
  },

  async resume(schedule) {
    await request('POST', `${idPath(schedule)}/resume`)
  },

  async runNow(jobName) {
    await batch.run(jobName)
  },
}
