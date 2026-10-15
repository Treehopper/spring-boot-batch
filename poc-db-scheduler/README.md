# PoC: Spring Batch + db-scheduler

db-scheduler as scheduler, with schedules editable at runtime, db-scheduler-ui and a custom Batch Console for the same jobs.

## Quick start

From the repository root (the first build downloads Node.js for the custom UI, so it needs internet access):

```sh
mvn -pl poc-db-scheduler -am spring-boot:run
```

Stop it with Ctrl+C. Run only one PoC at a time; they all use port 8080. Don't run `mvn spring-boot:run` without
`-pl` in the root folder: Maven then starts every PoC, one after another.

| What | URL |
|------|-----|
| **Batch Console** (custom UI) | http://localhost:8080/batch-console/ |
| **db-scheduler-ui** (built-in dashboard) | http://localhost:8080/db-scheduler |
| UC4 API (user `uc4`, password `uc4-secret`) | http://localhost:8080/api/... ([how](../poc-common/README.md#external-scheduler-uc4-api-api)) |

More: [all endpoints](#endpoints), [failure mails](../README.md#run-a-poc) (optional local mail server).

## Tests, data, stack

```sh
mvn -pl poc-db-scheduler -am test          # integration tests
```

State (schedules, Spring Batch history, execution logs) lives in `poc-db-scheduler/data` (H2 files). Delete the folder for a fresh start with the demo schedules.
Stack: Spring Boot 4.0.6, Spring Batch 6.0, db-scheduler 16.12, db-scheduler-ui 5.0, H2, Java 21+. Jobs, Batch Console, UC4 API and notifications are shared via
[poc-common](../poc-common/README.md).

## How the schedules work

```
 Batch Console ──► /batch-console/api/schedules ──► DbSchedulerSchedules ──► SchedulerClient.reschedule(instance, new data)
                                                                                     │
 scheduled_tasks table: task "batch-schedule", one instance per schedule ◄──────────┘
   data = BatchSchedule(jobName, cronExpression, timeZone, paused)  ──►  getSchedule(): CronSchedule (Quartz style) or Paused
                                                                                     │
 db-scheduler polls, runs the instance ──► BatchJobStarter.runSafely(...) ──► Spring Batch job
```

- **Schedules are data, not code:** the task `batch-schedule` is a `RecurringTaskWithPersistentSchedule`. Each schedule
  is one instance of it (id `importCustomersJob-cron`, `cleanupJob-cron`), and its task data, a `BatchSchedule`
  record, holds job name, cron, time zone and paused flag. db-scheduler asks the data for the next execution time after
  every run. So changing a schedule means rescheduling the instance with new data. Everything stays in db-scheduler's
  own `scheduled_tasks` table; there is no extra table.
- **Quartz-style cron:** `CronSchedule` with `CronStyle.QUARTZ` (cron-utils' Quartz definition). The console's
  recurrence builder is therefore the same as in the Quartz PoC, including "second Tuesday" (`3#2`).
- **Pause:** the data's `paused` flag makes `getSchedule()` return `Paused`, a schedule whose next execution time is
  db-scheduler's `NEVER` (year 2500). The instance keeps its cron expression for resuming.
- **Run now and UC4 starts:** a one-time task `batch-run`, scheduled for now. With
  `db-scheduler.immediate-execution-enabled` it starts right away instead of at the next poll.
- **Seeding:** the two demo schedules are created on the first start only (`scheduleIfNotExists`); afterwards the
  stored, possibly edited ones apply.
- **Schema:** the starter doesn't create db-scheduler's table, so `schema.sql` does (from db-scheduler's
  `hsql_tables.sql`, adapted for H2).

Classes: `dbscheduler/DbSchedulerTasks` (the two tasks), `BatchSchedule` and `RunRequest` (task data),
`DbSchedulerSchedules` (the `ManagedSchedules` implementation and seeding), `DbSchedulerSupport` (cron check, run now,
scheduler info).

## Endpoints

| What | URL | Login |
|------|-----|-------|
| Batch Console | http://localhost:8080/batch-console/ | none (see [Security](#security)) |
| db-scheduler-ui | http://localhost:8080/db-scheduler | none |
| Swagger UI | http://localhost:8080/swagger-ui/index.html | the browser asks for `uc4` / `uc4-secret` when you call `/api/**` |
| H2 console: `BATCH_*` (Spring Batch) and `scheduled_tasks` (db-scheduler) tables | http://localhost:8080/h2-console | JDBC URL `jdbc:h2:file:./data/batch;AUTO_SERVER=TRUE`, user `sa`, empty password |
| Health | http://localhost:8080/actuator/health | none |

APIs: the Batch Console API (including `/batch-console/api/schedules`) and the UC4 API are shared by all PoCs, see
[poc-common](../poc-common/README.md#apis). The cron dialect is `quartz`.

## Findings

1. **Runtime-editable schedules work with db-scheduler's own means.** `RecurringTaskWithPersistentSchedule` stores the
   schedule with each task instance, and `reschedule(...)` with new data changes it. No table or scheduler state of
   our own is needed. The backend is a few small classes (see above).
2. **Time zone per schedule:** `CronSchedule(pattern, zoneId, style)` evaluates the expression in that zone, daylight
   saving time included.
3. **Quartz cron syntax is available** (`CronStyle.QUARTZ`), so one builder serves both Quartz and db-scheduler.
4. **A schedule can't be changed while it runs.** db-scheduler refuses to reschedule (or cancel) a task instance that is
   currently executing (`TaskInstanceCurrentlyExecutingException`). The schedules API answers `409` with "try again
   after the run", the console shows that message, and the state column shows `RUNNING`. With long-running jobs this
   is noticeable.
5. **No pause.** db-scheduler's disabled cron schedule (`"-"`) can't be used for it: it throws as soon as the next
   execution time is computed, which rescheduling does. The PoC uses its own `Paused` schedule instead.
6. **The default listing hides running executions.** `getScheduledExecutionsForTask(...)` leaves out picked
   (executing) instances, so a running schedule would vanish from the list. The PoC asks with
   `ScheduledExecutionsFilter.all()`.
7. **No per-job serialisation.** The scheduled instance and a "run now" (a separate one-time instance) can run the same
   batch job at the same time. Quartz queues them with `@DisallowConcurrentExecution`. Spring Batch allows it here,
   because every run has its own `requestId`.
8. **Task data is Java-serialized** (db-scheduler's default serializer). Changing the `BatchSchedule` record later
   breaks the stored data; for real use, configure a JSON serializer.
9. **db-scheduler-ui needs JSON-friendly task data.** With task data shown (`db-scheduler-ui.task-data: true`), it
   serializes each instance's data with Jackson. A self-reference in `ScheduleAndData.getData()` made its API fail
   (fixed in the PoC). Its API then lists every task instance with its data and next execution time. I haven't used
   its UI beyond loading it.
10. **db-scheduler-ui 5.0 shows an error page on Spring Boot 4 for all URLs except `/db-scheduler` and
    `/db-scheduler/history/all`** (e.g. `/db-scheduler/` or a reloaded task page). Its SPA fallback serves `index.html`
    as an in-memory resource, which Spring Framework 7 can't serve ("Byte array resource … cannot be resolved to
    absolute file path"). Workaround in this PoC: `DbSchedulerUiRoutesController` forwards the UI's routes to
    `/db-scheduler/index.html`. Missing files under `/db-scheduler/` still answer `500` instead of `404`.
11. **One scheduler thread per running job:** like Quartz, a task blocks its thread for the whole batch run
    (`db-scheduler.threads`).

## Security

PoC-level only, as in the other PoCs: `/api/**` requires the technical user `uc4`; the Batch Console, db-scheduler-ui,
Swagger and the H2 console are open. For real use, protect them with the host application's security (e.g. the
company SSO), and move the credentials out of `application.yml`.
