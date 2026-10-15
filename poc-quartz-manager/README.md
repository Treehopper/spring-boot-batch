# PoC: Spring Batch + Quartz + Quartz Manager

Spike of **option 1** from the [evaluation](../README.md#assessment):
Quartz + [Quartz Manager](https://github.com/fabioformosa/quartz-manager) + a Spring Batch listener for notifications,
with external (UC4) starts routed through Quartz.

It contains two UIs side by side:
- the **Quartz Manager UI** (`/quartz-manager-ui/`), as shipped by the library
- the **Batch Console** (`/batch-console/`), a small custom React UI that covers exactly the UI requirements:
  schedules with time zones, start/stop, logs and past executions. It is shared with the db-scheduler and JobRunr
  PoCs and described in [poc-common](../poc-common/README.md#batch-console).

Stack: Spring Boot 4.0.6, Spring Batch 6.0, Quartz 2.5 (JDBC job store), Quartz Manager 5.0.1, H2 (file DB), Java 21+.
The Spring Batch jobs, the Batch Console, the UC4 API and the notifications come from
[poc-common](../poc-common/README.md); this module adds Quartz and Quartz Manager.

## Run it

From the repository root (prerequisites, mail server and macOS notes: [Run a PoC](../README.md#run-a-poc)):

```sh
mvn -pl poc-quartz-manager -am spring-boot:run
```

The app listens on http://localhost:8080; see [Endpoints](#endpoints) for the UIs and APIs.
State (Spring Batch history, Quartz schedules, execution logs) lives in `poc-quartz-manager/data`. Delete it for a
fresh start.

### Automated checks

```sh
mvn -pl poc-quartz-manager -am test    # integration tests: start via API, failure mail, graceful stop, owner switch, ...
poc-quartz-manager/smoke-test.sh       # against the running app (needs curl and jq): walks through the UC4 API
```

## What's in it

```
 Quartz Manager UI ──┐                                         ┌── JobExecutionListener: email on FAILED/STOPPED
 Batch Console ──────┤
 cron triggers ──────┼─► Quartz job ─► BatchJobStarter ─► JobOperator.start(...) ─► Spring Batch job
 UC4: POST /api/... ─┘  (one per          (poc-common)                            └── JobRepository (BATCH_* tables)
                        batch job)
 UC4: GET status / POST stop ───────────────────────────► JobRepository / JobOperator
```

- **Seeded schedules:** `importCustomersJob` with cron `0 0/2 * * * ?` in `Europe/Vienna`, and `cleanupJob` with cron
  `0 30 2 * * ?` in `America/New_York`. They show the per-trigger time zone.
- **Quartz jobs** (`quartz/jobs`) extend `AbstractQuartzManagerJob`. Each one is three lines that delegate to
  `SpringBatchQuartzJobRunner`, which:
  - starts the batch job via `BatchJobStarter`, the only class that calls `JobOperator`
  - returns a log record for the Quartz Manager log panel: status, duration, read/write/skip counts and who started the run
  - turns the Quartz job data map into Spring Batch job parameters
- **Live run events** (`BatchLiveLog`): Spring Batch listeners stream each run's progress to the Quartz Manager log
  panel while it runs. They are registered on every job and step automatically (`BatchLiveLogRegistrar`).
  A run looks like this in the panel (newest entry on top in the UI):

  ```
  [INFO]  importCustomersJob #10 started by api | parameters: failAtItem=7, itemDelayMs=10
  [INFO]  importCustomersStep started
  [INFO]  importCustomersStep: chunk 1 written, 5 items (5 total)
  [ERROR] importCustomersStep: chunk 2 failed and was rolled back: IllegalStateException: Simulated failure at customer 7
  [ERROR] importCustomersStep FAILED in 94 ms: read=10, written=5, skipped=0, commits=1, rollbacks=1 | IllegalStateException: ...
  [ERROR] importCustomersJob #10 FAILED in 98 ms (startedBy=api) | importCustomersStep: read=10, write=5, skip=0 | IllegalStateException: ...
  ```

  For jobs with many chunks, `poc.live-log.chunk-every: n` sends only every n-th chunk event, because the panel keeps
  only the last 50 entries.
- **Persistence:** Quartz uses Spring Boot's JDBC job store in the same H2 DB, so schedules edited in the UI survive restarts.
  Quartz Manager's own scheduler (RAM only) is disabled, and Boot's scheduler is aliased to `quartzManagerScheduler`
  (`QuartzConfig`).
- **Shared parts** from [poc-common](../poc-common/README.md): failure notifications, execution logs, the Batch
  Console and the UC4 API. `QuartzSchedulerSupport` plugs Quartz into them: the cron check uses Quartz's
  `CronExpression`, and "run now" and UC4 starts go through `Scheduler.triggerJob`.
- **UC4 owner switch** (Quartz only): hands a job's scheduling over to UC4, see [UC4 API](#uc4-api-and-batch-console-api).

## Endpoints

Everything runs on http://localhost:8080. There is no page at `/`.

### Web UIs

| What | URL | Login |
|------|-----|-------|
| Batch Console (custom React UI, [poc-common](../poc-common/README.md#batch-console)) | http://localhost:8080/batch-console/ | none (see [Security](#security)) |
| Quartz Manager UI | http://localhost:8080/quartz-manager-ui/ | none |
| Swagger UI: all REST endpoints below, with "Try it out" | http://localhost:8080/swagger-ui/index.html (or `/swagger-ui.html`) | the browser asks for `uc4` / `uc4-secret` when you call `/api/**` |
| H2 console: `BATCH_*` (Spring Batch history) and `QRTZ_*` (Quartz schedules) tables | http://localhost:8080/h2-console | JDBC URL `jdbc:h2:file:./data/batch;AUTO_SERVER=TRUE`, user `sa`, empty password |

### UC4 API and Batch Console API

Shared by all PoCs, see [poc-common](../poc-common/README.md#apis). Here, UC4 starts and "run now" go through Quartz
(`Scheduler.triggerJob`), so every run passes the same Quartz job as scheduled runs and shows up in Quartz Manager.
The console reads and changes schedules through the Quartz Manager API, not the neutral `/batch-console/api/schedules`.

Quartz-only additions to the UC4 API (HTTP basic, `uc4` / `uc4-secret`):

| Endpoint | Purpose |
|----------|---------|
| `GET /api/jobs/{job}` | Owner and Quartz triggers (key, state, cron, time zone, next fire time) |
| `PUT /api/jobs/{job}/owner` (`{"owner":"UC4"}` or `"INTERNAL"`) | Hands scheduling to UC4: pauses the job's triggers. A guard also skips scheduled fires while UC4 owns the job, in case someone resumes a trigger in the UI. Manual "trigger now" and API starts still run. |

### Quartz Manager API (`/quartz-manager/**`)

Provided by the Quartz Manager library and used by its UI. No login in this PoC. `{group}` is `batch` for the
seeded jobs and triggers.

| Area | Endpoints |
|------|-----------|
| Scheduler | `GET /quartz-manager/scheduler`; `POST /quartz-manager/scheduler/{start,standby,resume,shutdown}` |
| Job classes | `GET /quartz-manager/job-classes`: the classes offered in the UI (`ImportCustomersQuartzJob`, `CleanupQuartzJob`) |
| Jobs | `GET /quartz-manager/jobs`; `GET`, `POST` (create), `PUT` (update), `DELETE /quartz-manager/jobs/{group}/{name}`; `POST /quartz-manager/jobs/{group}/{name}/trigger` (run now) |
| Triggers | `GET /quartz-manager/triggers`; `GET`, `POST` (create), `PUT` (reschedule), `DELETE /quartz-manager/triggers/{group}/{name}`; `POST .../pause`, `POST .../resume` |
| Simple triggers (older API) | `GET`, `POST`, `PUT /quartz-manager/simple-triggers/{group}/{name}` |
| Calendars | `GET /quartz-manager/calendars`; `GET`, `POST`, `PUT`, `DELETE /quartz-manager/calendars/{name}`; `POST .../included-time-test` |

`GET /quartz-manager/auth/whoami` returns `404` on purpose: that tells the UI that login is disabled.

### WebSocket (used by the Quartz Manager UI)

STOMP over SockJS. The run events from [What's in it](#whats-in-it) arrive here.

| Endpoint | Topics |
|----------|--------|
| `/quartz-manager/logs` | `/topic/logs/{triggerName}`: log records of runs, e.g. `/topic/logs/importCustomersJob-cron` |
| `/quartz-manager/progress` | `/topic/progress/{triggerName}`: after each run, the times triggered and the previous, next and final fire time |

### Operations

| What | URL |
|------|-----|
| Health (mail health check disabled) | http://localhost:8080/actuator/health |
| Info | http://localhost:8080/actuator/info |
| OpenAPI document (JSON) | http://localhost:8080/v3/api-docs |

## Batch Console

See [poc-common](../poc-common/README.md#batch-console). In this PoC it uses the Quartz cron dialect, and changes
schedules through the Quartz Manager API. That API rebuilds a trigger on every change, which un-pauses it, so the
console pauses it again after saving (finding 10).

## Things to try in the Quartz Manager UI

How it fits together: a **job** (`batch.importCustomersJob`) says *what* runs, and a **trigger** says *when*.
One job can have several triggers. The PoC creates one cron trigger per job (`batch.importCustomersJob-cron`).

**Start a scheduled job right now (no parameters):**
open the job in the jobs list, then press **Trigger Now** in its detail drawer. This is the same as
`POST /quartz-manager/jobs/batch/importCustomersJob/trigger`. The run uses the job's own JobDataMap, and the cron
trigger stays unchanged.

**Add a manual one-off trigger (optionally with parameters):**
**Create Trigger**, then:
- Target type: **Existing stored job** → `batch` / `importCustomersJob`
- Trigger type: **Simple**, repeat count `0` (= run once), no start date (= now)
- optional **Trigger JobDataMap**, e.g. `itemCount=5` or `failAtItem=3`. The entries become Spring Batch job
  parameters for this run only.

It fires immediately and Quartz removes it afterwards, so it disappears from the trigger list. Its log line shows
up under the job's cron trigger (see finding 2). Same thing via the API:

```sh
curl -X POST -H 'Content-Type: application/json' localhost:8080/quartz-manager/triggers/batch/run-once \
     -d '{"triggerType":"SIMPLE","jobKey":{"name":"importCustomersJob","group":"batch"},"repeatCount":0,"repeatInterval":1000,"jobDataMap":{"itemCount":"5"}}'
```

Avoid **New job from class** for this. It creates a separate, unnamed job next to `batch.importCustomersJob`,
so the owner switch for UC4 doesn't apply to it.

**More to try:**
1. Select the `importCustomersJob-cron` trigger and keep the page open. Every 2 minutes a log line with status,
   duration and step counts appears.
2. Change the cron expression and time zone of a trigger, or add a second cron trigger to the same job.
   Restart the app: the change is still there.
3. Start a failing run (`failAtItem=3` in a one-off trigger, or `-d '{"failAtItem":"3"}'` with the UC4 `curl` in
   [poc-common](../poc-common/README.md#external-scheduler-uc4-api-api))
   and check the mail.
4. Pause the trigger, or put the scheduler in standby.

While a job is handed over to UC4 (`PUT /api/jobs/{job}/owner`), runs from its triggers are skipped, including
one-off triggers. **Trigger Now** and the UC4 API still start it.

## Findings

What the spike showed beyond the evaluation table:

1. **Quartz Manager does not keep logs or execution history.** Out of the box, a job sends one log record at the end
   of each run. This PoC adds live run events (see `BatchLiveLog`), but all records are pushed over WebSocket only
   (`/topic/logs/<triggerName>`), not stored. The UI clears them when you select another trigger or reload the page.
   This answers the open "to verify" point: nothing survives a restart, or even a page reload.
   Past executions and their runtime are therefore only available from the Spring Batch tables (`GET /api/executions/{id}`).
   A UI for them still needs to be built (option 3, hybrid).
2. **Runs from "trigger now" and UC4 are invisible in the UI's log panel by default.** `Scheduler.triggerJob` creates a
   one-off trigger (`MT_<random>`), and the log record goes to that trigger's topic, which the UI never subscribes to.
   Workaround in this PoC: also publish the record to the topics of the job's other triggers.
3. **Quartz Manager's own scheduler is RAM only.** Schedules edited in the UI are lost on restart. Its persistence
   starter supports PostgreSQL only. Workaround in this PoC: use Spring Boot's JDBC-backed Quartz scheduler and register
   it under the bean name Quartz Manager expects. This works with any database Quartz supports.
4. **Abstract job base classes leak into the UI.** Quartz Manager offers every subtype of `AbstractQuartzManagerJob`
   in the job class dropdown, including abstract ones. That's why the shared logic is a bean, not a base class.
5. **`AbstractQuartzManagerJob.execute` is `final`** and only sends a log record when `doIt` returns normally.
   The runner therefore catches everything and turns errors into `ERROR` log records.
6. **Quartz Manager replaces the `ConversionService`.** `@Value` injection of e.g. `Duration` fails;
   `@ConfigurationProperties` (see `PocProperties`) is unaffected.
7. **The Quartz worker thread is busy for the whole batch run**, because the job operator runs jobs synchronously.
   Size `org.quartz.threadPool.threadCount` accordingly. `@DisallowConcurrentExecution` queues a second start of the
   same job instead of running both at once.
8. **Refreshing the Quartz Manager UI gives a 404.** The Angular app changes the URL to routes such as
   `/quartz-manager-ui/manager` that don't exist on the server. Workaround in this PoC: `QuartzManagerUiController`
   serves `index.html` for those routes.
9. Spring Batch 6.0's JDBC `JobRepository.getJobExecution(id)` throws `EmptyResultDataAccessException` for unknown ids
   instead of returning `null`.
10. **Rescheduling through the Quartz Manager API un-pauses the trigger**, because it replaces the trigger with a new one.
    The Batch Console pauses it again after saving.
11. **The Quartz Manager API answers an invalid cron expression with a bare HTTP 500**, without a message. The trigger
    stays unchanged. The Batch Console checks the expression first (`/batch-console/api/cron-preview`).
12. **Quartz silently uses GMT for an unknown time zone id** (`TimeZone.getTimeZone`). The Batch Console rejects
    unknown zones before saving.

Gaps of the Quartz Manager UI that the Batch Console closes:

- history of past executions with runtime, step details and who started them
- logs of past executions, which survive a page reload and a restart
- graceful Spring Batch stop
- time zone shown next to each schedule (the Quartz Manager UI doesn't show it in its trigger detail view, per the
  evaluation; not re-checked in this spike)

Not covered by either UI: restart of failed executions, and the UC4 owner switch (UC4 API only).

## Security

PoC-level only:

- `/api/**` requires the technical user `uc4` (HTTP basic).
- Everything else is open: Batch Console and its API, Quartz Manager UI and API, Swagger and the H2 console. CSRF is
  disabled for them.

For real use, protect `/batch-console/**` and `/quartz-manager/**` with the host app's security (e.g. the company SSO),
or add `quartz-manager-starter-security` for the Quartz Manager part.
Also move the credentials out of `application.yml`.
