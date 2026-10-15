# poc-common: shared parts of the scheduler PoCs

Everything the three PoCs ([Quartz + Quartz Manager](../poc-quartz-manager/README.md),
[db-scheduler](../poc-db-scheduler/README.md), [JobRunr](../poc-jobrunr/README.md)) have in common, so that they
differ only in the scheduler:

- **demo Spring Batch jobs:** `importCustomersJob` (chunk step) and `cleanupJob` (tasklet), see `BatchJobsConfig`
- **one entry point for starting jobs:** `BatchJobStarter`, the only class that calls `JobOperator`
- **execution history, logs and stop:** on Spring Batch (`JobRepository`, `JobOperator`), plus one log file per
  execution
- **failure notifications:** `JobNotificationListener` sends an email for the statuses in `poc.notification.notify-on`
- **external-scheduler (UC4) API:** start (through the PoC's scheduler), status, stop
- **the Batch Console:** a small React UI with its backend, served by every PoC at `/batch-console/`

It is a plain library jar. The React app is built into it as `META-INF/resources/batch-console`, and
`PocContractTest` (in `src/test`) is published as a test jar for the PoCs' integration tests.

## How a PoC plugs in its scheduler

| Interface (`com.example.poc.scheduling`) | Provides | Quartz | db-scheduler | JobRunr |
|---|---|---|---|---|
| `SchedulerSupport` | scheduler name and links, cron dialect, cron check, "start now" through the scheduler | ✅ | ✅ | ✅ |
| `ManagedSchedules` | the neutral schedules API (`/batch-console/api/schedules`): list, reschedule, pause, resume | – (the console uses the Quartz Manager API) | ✅ | ✅ |

Scheduler tasks call `BatchJobStarter.runSafely(...)`, which never throws. A failed batch run is reported by Spring
Batch (status, notification), and doesn't trigger the scheduler's own retries.

## Batch Console

A deliberately small custom UI in React (`frontend/`), side by side with each scheduler's own UI. It implements only
what the [UI requirement](../README.md#requirements) asks for, and nothing else:

| Requirement | In the Batch Console | Backend used |
|-------------|----------------------|--------------|
| Change schedules at runtime, incl. time zone per schedule | **Schedules**: per job its cron schedules in words ("Every weekday (Mon–Fri) at 06:45") and as cron, with time zone, state and next run (in the schedule's zone and in local time). **Edit** opens a recurrence builder, see below. | Quartz: Quartz Manager API (`GET /jobs`, `GET` and `PUT /triggers/{group}/{name}`). db-scheduler, JobRunr: `GET` and `PUT /batch-console/api/schedules/{id}`. Cron check: `GET /batch-console/api/cron-preview` |
| Manual start | **Run now** per job. The new execution opens automatically. | Quartz: `POST /quartz-manager/jobs/{group}/{name}/trigger`. Others: `POST /batch-console/api/jobs/{job}/run` |
| Manual stop | **Stop** on a running execution: graceful Spring Batch stop. **Pause/Resume** per schedule. | `POST /batch-console/api/executions/{id}/stop`; pause/resume: Quartz Manager API or `/batch-console/api/schedules/{id}/pause` and `/resume` |
| View logs | **Log** of each execution, stored per execution, so also for past runs and after a restart. Refreshed every 2 s while the run is in progress. | `GET /batch-console/api/executions/{id}/log` |
| View past executions and their runtime | **Executions**: every run with status, who started it (schedule, manual, UC4 API), start and duration, filterable by job. **Details**: parameters, steps with read/write/skip/commit/rollback counts and duration, exit description. | `GET /batch-console/api/executions`, `GET /batch-console/api/executions/{id}` |

On load, the console asks `GET /batch-console/api/server` which scheduler it talks to. That gives it the scheduler's
name, the links to its own UI, the cron dialect for the recurrence builder, and where schedules come from
(`frontend/src/scheduleBackends.ts`).

Left out on purpose, because no requirement needs it:
- creating or deleting jobs and schedules
- other schedule types than cron (Quartz: simple, daily and calendar-interval triggers, calendars)
- misfire settings (edited schedules use "do nothing": a missed run is skipped)
- scheduler start, standby and shutdown
- job parameters for manual starts
- restarting failed executions
- the UC4 owner switch (Quartz PoC: stays in the UC4 API)
- live push updates (the console polls instead)

### Recurrence builder

Like the recurrence dialog in Outlook. Choose how often, then the details, and the builder writes the cron
expression in the dialect of the PoC's scheduler:

| Repeat | Details | Quartz and db-scheduler | JobRunr |
|--------|---------|-------------------------|---------|
| Every few minutes | every 1, 2, 5, 10, 15, 20 or 30 minutes | `0 0/15 * * * ?` | `0 0/15 * * * *` |
| Hourly | every 1, 2, 3, 4, 6, 8 or 12 hours, at minute m (counted from midnight) | `0 5 0/6 * * ?` | `0 5 0/6 * * *` |
| Daily | time | `0 30 2 * * ?` | `0 30 2 * * *` |
| Every weekday (Mon–Fri) | time | `0 45 6 ? * MON-FRI` | `0 45 6 * * MON-FRI` |
| Weekly | days of the week, time | `0 15 7 ? * MON,WED,FRI` | `0 15 7 * * MON,WED,FRI` |
| Monthly, on a day of the month | day 1–31 or the last day, time | `0 0 0 15 * ?`, `0 59 23 L * ?` | `0 0 0 15 * *`, `0 59 23 L * *` |
| Monthly, on a weekday | first, second, third, fourth or last Monday … Sunday, time | `0 0 9 ? * 3#2` (second Tuesday), `0 0 9 ? * 6L` (last Friday) | only the last weekday: `0 0 9 * * 5L` (JobRunr has no `#`) |
| Custom cron expression | any expression the scheduler accepts; "edit as cron" turns a built one into a starting point | | |

Quartz numbers the days of the week from Sunday = 1, JobRunr (Unix-style) from Sunday = 0, and JobRunr has no `?`.
The builder works on the Quartz form and translates for JobRunr (`frontend/src/cron.ts`).

Opening an existing schedule fills in the builder from its expression. Expressions that don't fit any of the shapes
above open as "Custom". Before saving, the scheduler's own cron parser checks the expression on the server
(`/batch-console/api/cron-preview`), and the editor lists the next three runs in the schedule's time zone, in yours and
in the server's. Save stays disabled until the check passes.

### Time zones

Times are entered in the schedule's own time zone, and the scheduler evaluates the schedule in that zone on the
server. All three support a time zone per schedule. "Use mine" sets it to the browser's zone. The console reads the
server's time zone (`GET /batch-console/api/server`) only to show the server's view; it does not convert the cron
expression to the server's offset.

Converting would break twice a year: between the US and EU daylight saving switches, the offset between, say,
Europe/Vienna and America/Los_Angeles is 8 instead of 9 hours, so a converted 09:00 Vienna schedule would fire at
08:00 Vienna for a few weeks. It could also shift runs to another weekday. A schedule stored as "09:00 Europe/Vienna"
fires at 09:00 Vienna all year, wherever the server runs.

### Logs

Every log statement during a run carries the MDC value `jobExecutionId` (set by `LaunchTrackingListener`).
`logback-spring.xml` routes those events with a `SiftingAppender` into one file per execution,
`data/execution-logs/<jobExecutionId>.log` (`poc.execution-logs.dir`). The console reads the file through the console
API. This includes Spring Batch's own messages, the job's log output, exceptions with stack traces and the
notification mail result.

The files keep the full timestamp with offset. The console shows each line in the browser's time zone as
`yyyy-MM-dd HH:mm:ss`, without milliseconds and thread name (`frontend/src/logFormat.ts`).

Files are not cleaned up yet. A real setup needs a retention rule, or a log store such as Loki or Elasticsearch
filtered by `jobExecutionId`.

### Layout and refresh

The execution list shows the latest 3 executions and scrolls for more. The selected execution opens below it, and
its log takes the remaining height of the window, at full width.

The console polls: schedules every 10 s, executions every 3 s, and the open execution with its log every 2 s while it
runs.

## APIs

### Batch Console API (`/batch-console/api/**`)

No login in the PoCs (see each PoC's security section).

| Endpoint | Purpose |
|----------|---------|
| `GET /batch-console/api/server` | The scheduler (name, cron dialect, schedule API, links to its UI) and the server's default time zone |
| `GET /batch-console/api/jobs` | The batch jobs with a description |
| `POST /batch-console/api/jobs/{job}/run` | Starts a job now, through the scheduler (`202` with a `requestId`) |
| `GET /batch-console/api/executions?jobName=&limit=50` | Latest executions, newest first, with status, who started them, start/end, duration, parameters and steps |
| `GET /batch-console/api/executions/{id}` | One execution, same fields |
| `GET /batch-console/api/executions/{id}/log` | The execution's log lines (plain text) |
| `POST /batch-console/api/executions/{id}/stop` | Graceful Spring Batch stop (`409` if not running) |
| `GET /batch-console/api/cron-preview?expression=&timeZone=` | Checks a cron expression and time zone with the scheduler's parser, returns the next 5 fire times |
| `GET /batch-console/api/schedules` | db-scheduler and JobRunr: all schedules with job, cron, time zone, state (`ACTIVE`, `PAUSED`, `RUNNING`) and next run |
| `PUT /batch-console/api/schedules/{id}` (`{"cronExpression": …, "timeZone": …}`) | Changes a schedule; a paused one stays paused. `400` for an invalid expression or zone, `409` if the scheduler can't change it right now (db-scheduler while it runs) |
| `POST /batch-console/api/schedules/{id}/pause`, `/resume` | Pauses or resumes a schedule |

### External-scheduler (UC4) API (`/api/**`)

HTTP basic with the technical user `uc4` / `uc4-secret` (`poc.api.*` in each PoC's `application.yml`).

| Endpoint | Purpose |
|----------|---------|
| `POST /api/jobs/{job}/start` (body: parameters as JSON object) | Starts the job **through the PoC's scheduler**. Waits up to `poc.api.start-timeout` for the execution and returns `202` with `jobExecutionId`. If the run hasn't started yet (e.g. queued), `jobExecutionId` is `null` and `statusUrl` points to `/api/requests/{requestId}`. |
| `GET /api/requests/{requestId}` | Resolves a queued start to its `jobExecutionId` |
| `GET /api/executions/{id}` | Status, exit code, duration, parameters and step counts, for UC4 to poll |
| `POST /api/executions/{id}/stop` | Graceful Spring Batch stop (`409` if not running) |

```sh
curl -u uc4:uc4-secret -H 'Content-Type: application/json' \
     -d '{"itemCount":"50","itemDelayMs":"500"}' localhost:8080/api/jobs/importCustomersJob/start
curl -u uc4:uc4-secret localhost:8080/api/executions/1
curl -u uc4:uc4-secret -X POST localhost:8080/api/executions/1/stop
```

Job parameters of `importCustomersJob`:
- `itemCount` (default 20)
- `itemDelayMs` (default 200), to make runs long enough to stop
- `failAtItem`, to make a run fail

## Building the frontend

No Node.js installation is needed. On the first build, `frontend-maven-plugin` downloads Node.js (from nodejs.org)
and npm (from the npm registry) into `frontend/node/`. It then runs `npm install` and `npm run build`, and in the
`test` phase the frontend unit tests (`npm test`, Vitest: cron mapping and log formatting). `-Dskip.frontend` skips
all of that, e.g. when only Java code changed.

npm 10 (bundled with Node 22) fails to install the dependencies ("Cannot read properties of null (reading
'edgesOut')"), so the build uses its own npm 11. The versions are set by `frontend.node.version` and
`frontend.npm.version` in `pom.xml`.

Inside the devcontainer, the firewall blocks nodejs.org. Add `nodejs.org` to the domain list in
`.devcontainer/init-firewall.sh` on the host and restart the container, or build with `-Dskip.frontend` there.

**Frontend development:** start one of the PoCs on port 8080, then the Vite dev server with hot reload on
http://localhost:5173/batch-console/. It proxies the API calls to port 8080:

```sh
cd poc-common
mvn frontend:npm -Dfrontend.npm.arguments="run dev"   # uses the Node.js that Maven downloaded
mvn frontend:npm -Dfrontend.npm.arguments=test
```

With a local Node.js 20.19+ or 22.12+ and npm 11, `npm run dev`, `npm test` and `npm run typecheck` in
`poc-common/frontend/` work as well.
