# PoC: Spring Batch + JobRunr

Shows that [JobRunr](https://github.com/jobrunr/jobrunr) (open-source edition, LGPL-3.0) recurring jobs can be changed
at runtime, including a time zone per schedule, with a small backend. The evaluation listed "recurring jobs defined in
code" as JobRunr's main disadvantage. The same [Batch Console](../poc-common/README.md#batch-console) as in the other
PoCs controls them.

Two UIs side by side:
- the **Batch Console** (`/batch-console/`): schedules with the recurrence builder, run now, stop, history and logs
- the **JobRunr dashboard** (http://localhost:8000/dashboard), JobRunr's own UI, for comparison. It runs on its own
  embedded web server and port, not in Spring MVC.

Stack: Spring Boot 4.0.6, Spring Batch 6.0, JobRunr 9.0 (Spring Boot 4 starter, SQL storage), H2 (file DB), Java 21+.
Spring Batch jobs, Batch Console, UC4 API and notifications come from [poc-common](../poc-common/README.md).

## Run it

From the repository root (prerequisites, mail server and macOS notes: [Run a PoC](../README.md#run-a-poc)):

```sh
mvn -pl poc-jobrunr -am spring-boot:run
mvn -pl poc-jobrunr -am test       # integration tests (PocContractTest)
```

State (Spring Batch history, schedules, JobRunr's jobs, execution logs) lives in `poc-jobrunr/data`. Delete it for a
fresh start.

## How the schedules work

```
 Batch Console ──► /batch-console/api/schedules ──► JobRunrSchedules ──► batch_schedule table (source of truth)
                                                           │
                                                           ├─ active: jobScheduler.scheduleRecurrently("<id>.v<version>", cron, zone,
                                                           │                                  () -> jobs.runScheduled(jobName, id))
                                                           └─ outdated versions / paused: deleteRecurringJob + delete their pending runs

 JobRunr background server ──► JobRunrBatchJobs.runScheduled(...) ──► BatchJobStarter.runSafely(...) ──► Spring Batch job
```

- **Recurring jobs are created at runtime:** `JobScheduler.scheduleRecurrently(id, cron, ZoneId, lambda)` can be
  called at any time, not only at startup. JobRunr stores the job as "class + method + arguments"
  (`JobRunrBatchJobs.runScheduled(jobName, scheduleId)`) and looks the bean up in Spring when it runs.
- **Own schedule table:** JobRunr OSS can't pause a recurring job, only delete it. So `batch_schedule` (id, job, cron,
  time zone, paused, version) is the source of truth, and every change is synced to JobRunr. An active schedule has a
  recurring job, a paused one has none.
- **A new recurring job id per change:** `<schedule id>.v<version>`, and outdated versions are deleted together with
  their pending runs. See findings 3 and 4 for why.
- **Run now and UC4 starts:** `jobScheduler.enqueue(...)`. The background server picks them up within its poll
  interval (5 s, JobRunr's minimum), so `poc.api.start-timeout` is 10 s here.
- **Retries off:** JobRunr retries failed jobs 10 times by default. Batch failures are handled by Spring Batch, so
  retries are set to 0, and the job methods never throw anyway.
- **Seeding:** the demo schedules are inserted on the first start only; on every start, JobRunr is synced to the table.

Classes: `jobrunr/JobRunrSchedules` (the `ManagedSchedules` implementation, sync and seeding), `JobRunrBatchJobs` (the
methods JobRunr jobs call), `JobRunrSupport` (cron check, run now, scheduler info), `schema.sql` (`batch_schedule`).

## Endpoints

| What | URL | Login |
|------|-----|-------|
| Batch Console | http://localhost:8080/batch-console/ | none (see [Security](#security)) |
| JobRunr dashboard | http://localhost:8000/dashboard | none |
| Swagger UI | http://localhost:8080/swagger-ui/index.html | the browser asks for `uc4` / `uc4-secret` when you call `/api/**` |
| H2 console: `BATCH_*` (Spring Batch), `JOBRUNR_*` (JobRunr) and `BATCH_SCHEDULE` tables | http://localhost:8080/h2-console | JDBC URL `jdbc:h2:file:./data/batch;AUTO_SERVER=TRUE`, user `sa`, empty password |
| Health | http://localhost:8080/actuator/health | none |

APIs: the Batch Console API (including `/batch-console/api/schedules`) and the UC4 API are shared by all PoCs, see
[poc-common](../poc-common/README.md#apis). The cron dialect is `jobrunr`.

## Findings

1. **Runtime-editable schedules work, with a schedule table of our own.** `scheduleRecurrently` at runtime, with a
   time zone per recurring job, covers creating and changing. The OSS edition has no pause/resume for recurring jobs,
   so the PoC keeps the schedules in its own table and deletes or re-creates the recurring job.
2. **JobRunr's cron is Unix-style:** Sunday = 0, no `?`, no `#`. "The second Tuesday of the month" can't be expressed
   at all; "the last Friday" can (`5L`). The console's builder offers only "last weekday" for JobRunr, and its cron
   check uses JobRunr's own parser.
3. **Replacing a recurring job leaves its pre-planned run in place.** When no run falls into the current poll window,
   JobRunr creates the next run ahead of time as a SCHEDULED job ("Ahead of time by recurring job"). Replacing or
   deleting the recurring job doesn't touch that job. It still fires at the old time, also after a pause. JobRunr also
   skips new runs of the recurring job while it is pending. The PoC deletes the pending runs of a schedule on every
   change.
4. **Replacing a recurring job under the same id can be ignored until the old next run.** The background server
   remembers, in memory, the last run it planned per recurring job id, and plans nothing for that id until that time
   has passed. Measured: a daily schedule changed to "every 5 seconds" didn't run at all within 60 s (it would have
   waited until the old daily run time, or a restart). With a new id per change (`<id>.v<version>`), the first run
   came 7 s after the change.
5. **A recurring run is skipped while the previous one is still running or queued** ("Run will be skipped as job is
   taking longer than given CronExpression or Interval"). Runs don't pile up, but slots are dropped without a trace in
   the Spring Batch history.
6. **Latency:** due runs and "run now" start within the background server's poll interval, 5 s at the minimum. The
   console waits for the new execution accordingly.
7. **Jobs are stored as method calls.** Renaming `JobRunrBatchJobs` or its methods breaks stored recurring and pending
   jobs. Keep these methods stable, or re-create the recurring jobs (which the startup sync does for the current
   version).
8. **Defaults to change:** 10 retries for failed jobs (set to 0), and anonymous usage data sent to JobRunr
   (`jobrunr.miscellaneous.allow-anonymous-data-usage: false`).
9. **The dashboard is a separate web server** on port 8000, outside Spring Security. It has its own basic-auth
   settings (`jobrunr.dashboard.username/password`), or it must be disabled or put behind a proxy.
10. **One worker per running job:** like the other schedulers, a job blocks its worker for the whole batch run
    (`jobrunr.background-job-server.worker-count`).

## Security

PoC-level only: `/api/**` requires the technical user `uc4`; the Batch Console, Swagger, the H2 console and the
JobRunr dashboard are open. For real use, protect the console with the host application's security (e.g. the
company SSO), secure or disable the dashboard (finding 9), and move the credentials out of `application.yml`.
