# PoC: Spring Batch + Quartz + Quartz Manager

Spike of **option 1** from the [evaluation](../README.md#assessment):
Quartz + [Quartz Manager](https://github.com/fabioformosa/quartz-manager) + a Spring Batch listener for notifications,
with external (UC4) starts routed through Quartz.

Stack: Spring Boot 4.0.6, Spring Batch 6.0, Quartz 2.5 (JDBC job store), Quartz Manager 5.0.1, H2 (file DB), Java 21+.

## Run it

Prerequisites: Java 21+ and Maven. To receive the notification mails you also need a local SMTP server on port 1025 (see below).

```sh
cd poc-quartz-manager

# optional: SMTP server that catches mails, web UI on http://localhost:8025 (needs Docker, so run it on the host)
docker compose up -d

mvn spring-boot:run
```

Without Docker (e.g. inside the devcontainer), use GreenMail instead. Its API UI on http://localhost:8099 lists the
received mails (`GET /api/user/ops@example.com/messages/INBOX/`).

```sh
curl -sfO https://repo1.maven.org/maven2/com/icegreen/greenmail-standalone/2.1.14/greenmail-standalone-2.1.14.jar
java -Dgreenmail.smtp.port=1025 -Dgreenmail.smtp.hostname=127.0.0.1 -Dgreenmail.auth.disabled \
     -Dgreenmail.api.port=8099 -Dgreenmail.api.hostname=127.0.0.1 -jar greenmail-standalone-2.1.14.jar
```

If no SMTP server is running, the app still works. The failed mail delivery is only logged.

The app listens on http://localhost:8080; see [Endpoints](#endpoints) for the UIs and APIs.
State (Spring Batch history and Quartz schedules) lives in `./data`. Delete it for a fresh start.

### macOS

The app runs directly on the Mac JVM. Docker is only used for Mailpit, and Mailpit is also available from Homebrew,
so neither Docker nor Colima is needed:

```sh
brew install maven jq mailpit   # maven pulls in a current OpenJDK (21+ is required)

mailpit                         # terminal 1: SMTP on localhost:1025, web UI on http://localhost:8025

cd poc-quartz-manager
mvn spring-boot:run             # terminal 2
./smoke-test.sh                 # terminal 3; works with the macOS default bash 3.2
```

`mvn -v` shows the JDK Maven uses; it must be Java 21 or newer.

To run Mailpit in a container with Colima instead, install Compose as well:

```sh
brew install colima docker docker-compose
colima start                    # also switches the docker context to "colima"
docker-compose up -d            # Mailpit has linux/arm64 and linux/amd64 images; Colima forwards the ports to localhost
```

`docker compose` (with a space) also works once `~/.docker/config.json` contains
`"cliPluginsExtraDirs": ["<output of brew --prefix>/lib/docker/cli-plugins"]`. Add that entry to the existing file
rather than replacing the file, which would drop the `colima` context.


### Automated checks

```sh
mvn test             # integration tests: start via API, failure mail, graceful stop, owner switch, ...
./smoke-test.sh      # against the running app (needs curl and jq): walks through the API scenarios below
```

## What's in it

```
 Quartz Manager UI ──┐                                         ┌── JobExecutionListener: email on FAILED/STOPPED
 cron triggers ──────┼─► Quartz job ─► BatchJobStarter ─► JobOperator.start(...) ─► Spring Batch job
 UC4: POST /api/... ─┘  (one per          (single entry point)                    └── JobRepository (BATCH_* tables)
                        batch job)
 UC4: GET status / POST stop ───────────────────────────► JobRepository / JobOperator
```

- **Two demo jobs:** `importCustomersJob` (chunk step; cron `0 0/2 * * * ?` in `Europe/Vienna`) and `cleanupJob`
  (tasklet; cron `0 30 2 * * ?` in `America/New_York`). The seeded schedules show the per-trigger time zone.
  Job parameters control the behaviour of `importCustomersJob`:
  - `itemCount` (default 20)
  - `itemDelayMs` (default 200), to make runs long enough to stop
  - `failAtItem`, to make a run fail
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
- **Notifications:** `JobNotificationListener` sends an email for the statuses in `poc.notification.notify-on`
  (default `FAILED,STOPPED`). The mail contains the parameters, step counts and the root cause.
- **UC4 API** for an external scheduler: start through Quartz, poll status, stop, hand over scheduling.
  See [UC4 API](#uc4-api-api).

## Endpoints

Everything runs on http://localhost:8080. There is no page at `/`.

### Web UIs

| What | URL | Login |
|------|-----|-------|
| Quartz Manager UI | http://localhost:8080/quartz-manager-ui/ | none (see [Security](#security)) |
| Swagger UI: all REST endpoints below, with "Try it out" | http://localhost:8080/swagger-ui/index.html (or `/swagger-ui.html`) | the browser asks for `uc4` / `uc4-secret` when you call `/api/**` |
| H2 console: `BATCH_*` (Spring Batch history) and `QRTZ_*` (Quartz schedules) tables | http://localhost:8080/h2-console | JDBC URL `jdbc:h2:file:./data/batch;AUTO_SERVER=TRUE`, user `sa`, empty password |
| Mailpit: caught notification mails (if running) | http://localhost:8025 | none |
| GreenMail API: alternative to Mailpit (if running) | http://localhost:8099 | none |

### UC4 API (`/api/**`)

For an external scheduler. HTTP basic with the technical user `uc4` / `uc4-secret` (`poc.api.*` in `application.yml`).

| Endpoint | Purpose |
|----------|---------|
| `POST /api/jobs/{job}/start` (body: parameters as JSON object) | Starts the job **through Quartz** (`Scheduler.triggerJob`). Waits up to 5 s for the execution and returns `202` with `jobExecutionId`. If the run is queued (e.g. a previous run is still active), `jobExecutionId` is `null` and `statusUrl` points to `/api/requests/{requestId}`. |
| `GET /api/requests/{requestId}` | Resolves a queued start to its `jobExecutionId` |
| `GET /api/executions/{id}` | Status, exit code, duration, parameters and step counts, for UC4 to poll |
| `POST /api/executions/{id}/stop` | Graceful Spring Batch stop (`409` if not running) |
| `GET /api/jobs/{job}` | Owner and Quartz triggers |
| `PUT /api/jobs/{job}/owner` (`{"owner":"UC4"}` or `"INTERNAL"`) | Hands scheduling to UC4: pauses the job's triggers. A guard also skips scheduled fires while UC4 owns the job, in case someone resumes a trigger in the UI. Manual "trigger now" and API starts still run. |

```sh
curl -u uc4:uc4-secret -H 'Content-Type: application/json' \
     -d '{"itemCount":"50","itemDelayMs":"500"}' localhost:8080/api/jobs/importCustomersJob/start
curl -u uc4:uc4-secret localhost:8080/api/executions/1
curl -u uc4:uc4-secret -X POST localhost:8080/api/executions/1/stop
```

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

## Things to try in the UI

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
3. Start a failing run (`failAtItem=3` in a one-off trigger, or `-d '{"failAtItem":"3"}'` with the API `curl` above)
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

Remaining gaps compared to options 2/3:

- no history view
- no step details in the UI (only in the log line)
- no graceful stop or restart from the UI (only via the API)
- time zone not shown in the UI's trigger detail view (per the evaluation; not re-checked in this spike)

## Security

PoC-level only:

- `/api/**` requires the technical user `uc4` (HTTP basic).
- Everything else is open: Quartz Manager UI and API, Swagger and the H2 console. CSRF is disabled for them.

For real use, protect `/quartz-manager/**` with the host app's security, or add `quartz-manager-starter-security`.
Also move the credentials out of `application.yml`.
