# spring-boot-batch

Evaluation of options for **controlling** and **monitoring** Spring Batch jobs running inside a Spring Boot application.

## Requirements

1. **Open source only (no commercial tools), decently maintained**, licence allows use in commercial (closed-source) products.
2. **Fully embedded** in the Spring Boot app – no extra server/microservice.
3. **UI** that supports:
   - changing schedules (cron etc.) at runtime
   - manual start / stop
   - viewing logs
   - viewing past executions and their runtime

## Why scheduling matters here

Spring Batch has no scheduler of its own; it only runs jobs (`JobOperator`) and records their history
(`JobRepository`, `BATCH_*` tables). Changing schedules at runtime therefore needs a scheduler with a
persistent, mutable trigger store, such as **Quartz**, **db-scheduler** or **JobRunr**. Spring's
`@Scheduled` is static and not editable at runtime. The UI candidates below are mostly scheduler UIs.
Each scheduled task simply calls `JobOperator.start(...)`.

## Candidates

Maintenance data from GitHub as of 2026-10-14.

| Candidate | Licence | Maintained | Embedded | Edit schedule | Start / stop | Logs | Past executions / runtime |
|-----------|---------|------------|:--------:|:-------------:|:------------:|:----:|:-------------------------:|
| **Quartz + [Quartz Manager](https://github.com/fabioformosa/quartz-manager)** | Apache-2.0 | ✅ v5.x, Boot 3.5/4.0, small team | ✅ starter + webjar UI | ✅ cron/simple/calendar triggers, pause/resume | ✅ trigger now; stop = scheduler standby/pause | ✅ live via WebSocket (`LogRecord`s from the job) | ⚠️ Quartz-level only, no Spring Batch step details |
| **[db-scheduler](https://github.com/kagkarlsson/db-scheduler) + [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui)** | Apache-2.0 | ✅ active, Boot 3.4/4.0 starters | ✅ starter, UI at `/db-scheduler` | ⚠️ reschedule/run now; recurring schedules are defined in code | ✅ run now; ⚠️ no stop of a running task | ⚠️ failure messages/stack traces in history | ✅ optional execution-log table with duration |
| **[JobRunr](https://github.com/jobrunr/jobrunr) (OSS)** | LGPL-3.0 (OK if used unmodified as a library) | ✅ very active | ✅ embedded dashboard | ⚠️ recurring jobs defined in code | ✅ trigger now; ⚠️ no stop | ✅ per-job logging/progress in dashboard | ✅ succeeded/failed jobs with timing |
| **Custom UI on Spring Batch APIs** (`JobOperator`, `JobRepository`) + Quartz | Apache-2.0 (Spring) | ✅ Spring core projects | ✅ | ✅ (via Quartz API) | ✅ incl. **graceful stop/restart of Spring Batch executions** | ✅ e.g. per-execution log via MDC (`jobExecutionId`) | ✅ full job/step history, read/write/skip counts |
| **Spring Boot Admin** | Apache-2.0 | ✅ | ❌ separate server app | ❌ | ❌ | ✅ log file viewer | ❌ (metrics only) |
| **Actuator + Micrometer (Prometheus/Grafana)** | Apache-2.0 | ✅ | ✅ (Grafana is external) | ❌ | ❌ | ❌ | ⚠️ metrics/aggregates only |

### Excluded

| Candidate | Reason |
|-----------|--------|
| Spring Batch Admin | EOL, archived 2019 |
| Spring Batch Lightmin | Last push Sep 2024, targets old Boot versions; client/server architecture |
| Spring Boot Admin | Requires its own server application (fails "no extra microservice") |

## Assessment

No off-the-shelf OSS tool covers **both** Spring Batch internals (step history, graceful stop, restart) **and**
editable scheduling in one embedded UI. Most promising options:

1. **Quartz + Quartz Manager** – closest out-of-the-box fit for scheduling, manual trigger and live logs.
   Gaps: Spring Batch execution history and a proper Spring Batch stop/restart.
   Jobs must extend `AbstractQuartzManagerJob`, a thin wrapper that calls `JobOperator`.
   Risk: small maintainer base.
2. **Quartz + small custom UI on Spring Batch APIs** – full coverage of all requirements and no third-party UI dependency,
   at the cost of building and maintaining a few screens (job list, schedule editor, execution history, log view).
3. **Hybrid** – Quartz Manager for scheduling plus a small custom page for Spring Batch execution history, stop/restart
   and logs.

Next step: build a spike of option 1 in this repo and check the gaps against option 2/3.

## References

- [Quartz Manager](https://github.com/fabioformosa/quartz-manager)
- [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui)
- [JobRunr licence](https://github.com/jobrunr/jobrunr/blob/master/License.md)
- [Spring Batch observability reference](https://docs.spring.io/spring-batch/reference/spring-batch-observability.html)
- [Spring Batch Admin (archived)](https://github.com/spring-projects/spring-batch-admin)
