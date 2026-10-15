# spring-boot-batch

Evaluation of options for **controlling** and **monitoring** Spring Batch jobs running inside a Spring Boot application.

## Table of contents

- [Development environment (devcontainer)](#development-environment-devcontainer)
  - [What's inside](#whats-inside)
  - [Prerequisites](#prerequisites)
  - [Getting started](#getting-started)
  - [Network firewall](#network-firewall)
  - [Read-only files](#read-only-files)
  - [Security note](#security-note)
- [Requirements](#requirements)
- [Why scheduling matters here](#why-scheduling-matters-here)
- [Candidates](#candidates)
  - [Notifications](#notifications)
  - [UC4 / external scheduler integration](#uc4--external-scheduler-integration)
  - [Excluded](#excluded)
- [Assessment](#assessment)
- [Proofs of concept](#proofs-of-concept)
  - [Run a PoC](#run-a-poc)
  - [Comparison](#comparison)
- [References](#references)

## Development environment (devcontainer)

The repo ships a [devcontainer](https://containers.dev/) in `.devcontainer/`.
It gives you a sandboxed environment for working on the repo with [Claude Code](https://docs.claude.com/en/docs/claude-code), with outbound network access restricted by a firewall.

### What's inside

- **Base image:** `node:20` (Debian), running as the non-root user `node`
- **Java 25** with **Maven** and **Gradle** (devcontainer feature `ghcr.io/devcontainers/features/java`)
- **Claude Code** (`@anthropic-ai/claude-code`, version set by the `CLAUDE_CODE_VERSION` build arg)
- **Tools:** git, git-delta, GitHub CLI (`gh`), zsh with Powerlevel10k, fzf, jq, nano and vim
- **VS Code extensions:** Claude Code and GitLens, plus ESLint and Prettier
- **Default shell:** zsh

### Prerequisites

- Docker (Docker Desktop, OrbStack, Colima or similar)
- One of:
  - VS Code with the [Dev Containers](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension
  - the [devcontainer CLI](https://github.com/devcontainers/cli) (`npm install -g @devcontainers/cli`)
  - another IDE with devcontainer support (e.g. IntelliJ IDEA)

### Getting started

**VS Code:** open the repo folder and run **Dev Containers: Reopen in Container** from the command palette.

**CLI:**

```sh
devcontainer up --workspace-folder .
devcontainer exec --workspace-folder . zsh
```

The repo is mounted at `/workspace`. On every container start, `postStartCommand` runs `init-firewall.sh`.
The container is ready once the firewall has been verified.

Inside the container, start Claude Code with `claude` and log in on first use.
The Claude config (`/home/node/.claude`) and shell history are stored in Docker volumes,
so the login and history survive container rebuilds.

The time zone comes from the host's `TZ` environment variable (default `America/Los_Angeles`).

### Network firewall

`.devcontainer/init-firewall.sh` sets up `iptables`/`ipset` rules (hence `--cap-add=NET_ADMIN` and `NET_RAW`) that
**drop all outbound traffic by default**. Only the following are allowed:

- DNS, SSH (port 22), localhost and the host's `/24` network
- GitHub (web, API and git IP ranges from `api.github.com/meta`), `raw.githubusercontent.com`, `objects.githubusercontent.com`
- Maven Central, Sonatype Central and the Gradle services and plugin portal
- npm registry
- Anthropic API, plus Sentry and Statsig (used by Claude Code)
- VS Code marketplace and update servers
- Documentation sites for the evaluated tools: spring.io, Quartz, JobRunr, Spring Boot Admin, Micrometer, Prometheus and Grafana

The script checks the setup on startup: `https://example.com` must be unreachable and `https://api.github.com` reachable.

To allow another host, add it to the domain list in `init-firewall.sh` **on the host**, then rebuild or restart the container.
Domains are resolved to IPs only once, at container start, so a host whose IPs change may need a restart.

Other repositories, such as `repo.spring.io` for Spring milestones/snapshots, are blocked until you add them.

### Read-only files

These paths are bind-mounted **read-only** inside the container,
so code running there (including Claude Code) cannot change its own sandbox or your git setup:

- `.devcontainer/`
- `.git/hooks/`
- `.git/config`

As a consequence, commands that write to the repo's `.git/config` fail inside the container.
Examples are `git remote add`, `git config user.name ...` without `--global`, and `git push -u`.
Make such changes on the host, or use `git config --global` inside the container.

### Security note

The firewall and read-only mounts reduce the risk of running Claude Code with fewer permission prompts
(e.g. `claude --dangerously-skip-permissions`), but they don't eliminate it.
Anything inside the container can still read the mounted repo, its credentials and the allowed hosts.
Only use it with repositories you trust.

## Requirements

1. **Open source only (no commercial tools), decently maintained**, licence allows use in commercial (closed-source) products.
2. **Fully embedded** in the Spring Boot app – no extra server/microservice.
3. **UI** that supports:
   - changing schedules (cron etc.) at runtime, **including the time zone per schedule**
   - manual start / stop
   - viewing logs
   - viewing past executions and their runtime
4. **Notifications**, e.g. when a run fails – at least via **email**.
5. **Open to an external scheduler later:** it must be possible to hand scheduling over to **UC4 (Automic Workload Automation)** later
   (see [UC4 / external scheduler integration](#uc4--external-scheduler-integration)).

## Why scheduling matters here

Spring Batch has no scheduler of its own; it only runs jobs (`JobOperator`) and records their history
(`JobRepository`, `BATCH_*` tables). Changing schedules at runtime therefore needs a scheduler with a
persistent, mutable trigger store, such as **Quartz**, **db-scheduler** or **JobRunr**. Spring's
`@Scheduled` is static and not editable at runtime. The UI candidates below are mostly scheduler UIs.
Each scheduled task simply calls `JobOperator.start(...)`.

## Candidates

Maintenance data from GitHub as of 2026-10-14.

| Candidate | Licence | Maintained | Embedded | Edit schedule | Time zones | Start / stop | Logs | Past executions / runtime | Notifications |
|-----------|---------|------------|:--------:|:-------------:|:----------:|:------------:|:----:|:-------------------------:|:-------------:|
| **Quartz + [Quartz Manager](https://github.com/fabioformosa/quartz-manager)** | Apache-2.0 | ✅ v5.x, Boot 3.5/4.0, small team | ✅ starter + webjar UI | ✅ cron/simple/calendar triggers, pause/resume | ✅ time zone field for cron and calendar-interval triggers (API + UI); ⚠️ not yet shown in the trigger detail view | ✅ trigger now; stop = scheduler standby/pause | ✅ live via WebSocket (`LogRecord`s from the job) | ⚠️ Quartz-level only, no Spring Batch step details | ❌ none built in; use a listener |
| **[db-scheduler](https://github.com/kagkarlsson/db-scheduler) + [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui)** | Apache-2.0 | ✅ active, Boot 3.4/4.0 starters | ✅ starter, UI at `/db-scheduler` | ⚠️ recurring schedules are defined in code by default; ✅ runtime-editable with `RecurringTaskWithPersistentSchedule` and a small backend ([PoC](poc-db-scheduler/README.md)); ⚠️ not while a run is in progress | ✅ `CronSchedule(expr, ZoneId)` per schedule, editable at runtime in the PoC; Quartz cron syntax available | ✅ run now; ⚠️ no stop of a running task | ⚠️ failure messages/stack traces in history | ✅ optional execution-log table with duration | ❌ none built in; use a listener |
| **[JobRunr](https://github.com/jobrunr/jobrunr) (OSS)** | LGPL-3.0 (OK if used unmodified as a library) | ✅ very active | ✅ embedded dashboard | ⚠️ recurring jobs defined in code by default; ✅ `scheduleRecurrently` at runtime plus an own schedule table ([PoC](poc-jobrunr/README.md)); ⚠️ no pause in OSS, pitfalls when replacing a recurring job | ✅ `ZoneId` per recurring job, editable at runtime in the PoC; ⚠️ Unix cron without "nth weekday" | ✅ trigger now; ⚠️ no stop | ✅ per-job logging/progress in dashboard | ✅ succeeded/failed jobs with timing | ❌ none built in; use a job filter |
| **Quartz + Quartz Manager + Spring Batch listener** (UC4 routed through Quartz) | Apache-2.0 | ✅ (see above) | ✅ | ✅ Quartz Manager UI | ✅ set in UI; ⚠️ not shown in trigger detail view | ✅ trigger now; ⚠️ stop = pause/standby, no graceful Spring Batch stop/restart | ✅ live via WebSocket | ⚠️ all runs visible (incl. UC4) but Quartz-level only, no step details | ✅ `JobExecutionListener` + email |
| **Custom UI on Spring Batch APIs** (`JobOperator`, `JobRepository`) + Quartz | Apache-2.0 (Spring) | ✅ Spring core projects | ✅ | ✅ (via Quartz API) | ✅ Quartz `CronScheduleBuilder.inTimeZone(...)` | ✅ incl. **graceful stop/restart of Spring Batch executions** | ✅ e.g. per-execution log via MDC (`jobExecutionId`) | ✅ full job/step history, read/write/skip counts | ✅ `JobExecutionListener` + email (see below) |
| **Spring Boot Admin** | Apache-2.0 | ✅ | ❌ separate server app | ❌ | ❌ | ❌ | ✅ log file viewer | ❌ (metrics only) | ⚠️ status-change notifications (email etc.) via its server |
| **Actuator + Micrometer (Prometheus/Grafana)** | Apache-2.0 | ✅ | ✅ (Grafana is external) | ❌ | ❌ | ❌ | ❌ | ⚠️ metrics/aggregates only | ⚠️ via Grafana/Alertmanager alerts (external) |

### Notifications

None of the embedded candidates sends emails out of the box. The scheduler-independent way is a Spring Batch
`JobExecutionListener` that sends an email via `spring-boot-starter-mail` (`JavaMailSender`) in `afterJob(...)` when
the status is `FAILED` (or on any other status). Because it hooks into Spring Batch itself, it also covers manual starts,
and it runs inside the app with no extra service. Further channels (Slack, Teams, …) can be added the same way via webhooks.

### UC4 / external scheduler integration

Taking over scheduling with UC4 (Automic) later works best if **scheduling is kept separate from job execution** from the start:

- **One entry point for starting jobs.** A small service wraps `JobOperator`. The internal scheduler (e.g. Quartz) only calls this service.
  It never contains job logic.
- **REST API for external schedulers** on top of that service, secured with a technical user:
  - `POST` start job (with parameters), returning the `jobExecutionId`
  - `GET` execution status and exit status, so UC4 can poll until the run reaches a final status (`COMPLETED`, `FAILED`, …)
  - `POST` stop
  
  UC4 can call this via its web service agent, or via an OS job that runs `curl` and maps the final status to an exit code.
- **Per-job "owner" switch** (internal scheduler vs. UC4). When UC4 takes over a job, its internal trigger is paused or removed
  so the job isn't started twice.
- **History, logs and notifications based on Spring Batch, not on the scheduler.** That way, runs started by UC4 still show up
  in the UI and still trigger failure emails (`JobExecutionListener`).
- `CommandLineJobOperator` (Spring Batch 6) is an alternative for UC4 OS jobs. However, it starts a new JVM per run instead of
  using the running app, which conflicts with the embedded approach.

Impact on the candidates:

- **Quartz Manager:** only knows about runs started through Quartz. UC4-started runs would be invisible in its execution and log views,
  unless **UC4 starts jobs through Quartz**. To do that, the start endpoint calls `Scheduler.triggerJob(...)` on the job's Quartz job
  instead of calling `JobOperator` directly. Then every run (scheduled, manual, UC4) passes through Quartz and is visible in
  Quartz Manager. UC4 can still poll status via Spring Batch: the wrapper job records the `jobExecutionId`, and the start
  endpoint returns it. Notifications work in any case, because the `JobExecutionListener` sits on the Spring Batch layer.
- **db-scheduler-ui / JobRunr:** same limitation; their history only covers their own executions.
- **Custom UI on Spring Batch APIs:** sees all runs regardless of who started them; the best fit for this requirement.

### Excluded

| Candidate | Reason |
|-----------|--------|
| Spring Batch Admin | EOL, archived 2019 |
| Spring Batch Lightmin | Last push Sep 2024, targets old Boot versions; client/server architecture |
| Spring Boot Admin | Requires its own server application (fails "no extra microservice") |

## Assessment

No off-the-shelf OSS tool covers **both** Spring Batch internals (step history, graceful stop, restart) **and**
editable scheduling in one embedded UI. Most promising options:

1. **Quartz + Quartz Manager + Spring Batch listener, UC4 routed through Quartz** – least custom code.
   - Quartz Manager covers scheduling (incl. time zones), manual trigger and live logs.
   - A `JobExecutionListener` sends failure emails.
   - The UC4 start endpoint triggers the Quartz job, so UC4 runs show up in Quartz Manager too
     (see [UC4 / external scheduler integration](#uc4--external-scheduler-integration)).
   - Remaining gaps: Spring Batch step-level history and a graceful Spring Batch stop/restart.
   - Jobs must extend `AbstractQuartzManagerJob`, a thin wrapper that calls `JobOperator`.
   - Risk: small maintainer base.
   - Verified in the [PoC](poc-quartz-manager/README.md#findings): Quartz Manager does **not** keep past executions or
     logs. Log records are only pushed live over WebSocket and are gone after a page reload. Its own scheduler is
     RAM-only, but it can be pointed at a JDBC-backed Spring Boot Quartz scheduler.
2. **Quartz + small custom UI on Spring Batch APIs** – full coverage of all requirements and no third-party UI dependency,
   at the cost of building and maintaining a few screens (job list, schedule editor, execution history, log view).
3. **Hybrid** – Quartz Manager for scheduling plus a small custom page for Spring Batch execution history, stop/restart
   and logs.

Whichever option is chosen, the UC4 requirement means the job start API, the execution history and the notifications
should sit on the Spring Batch layer (`JobOperator`/`JobRepository`), not on the scheduler.
That keeps the scheduler replaceable.

All three schedulers can be driven by the same small custom UI. db-scheduler and JobRunr were listed with
"recurring schedules in code" as their main disadvantage. The [proofs of concept](#proofs-of-concept) show that both
can be changed at runtime, including a time zone per schedule, with a small backend of a few classes. Each has
caveats, though (see [Comparison](#comparison)).

## Proofs of concept

Four Maven modules, one Spring Boot app per scheduler, all with the same demo Spring Batch jobs and the same custom UI:

| Module | What |
|--------|------|
| [`poc-common`](poc-common/README.md) | Shared: demo Spring Batch jobs, single start entry point, execution history and per-execution logs, failure emails, the external-scheduler (UC4) API, and the **Batch Console**, a small React UI covering the UI requirements (schedules with a recurrence builder and time zones, run now, stop, history, logs) |
| [`poc-quartz-manager`](poc-quartz-manager/README.md) | Option 1: Quartz + Quartz Manager, with the Quartz Manager UI next to the Batch Console (which also makes it a spike of option 3) |
| [`poc-db-scheduler`](poc-db-scheduler/README.md) | db-scheduler with runtime-editable schedules, db-scheduler-ui next to the Batch Console |
| [`poc-jobrunr`](poc-jobrunr/README.md) | JobRunr OSS with runtime-editable schedules, JobRunr dashboard next to the Batch Console |

### Run a PoC

Prerequisites: Java 21+ and Maven. No Node.js installation is needed: on the first build, Maven downloads Node.js
and npm for the Batch Console into `poc-common/frontend/node/` (see [poc-common](poc-common/README.md#building-the-frontend)).
Inside the devcontainer, the firewall blocks nodejs.org; add it to `.devcontainer/init-firewall.sh` on the host, or
build with `-Dskip.frontend`.

From the repository root, one PoC at a time (all use port 8080):

```sh
mvn -pl poc-quartz-manager -am spring-boot:run
mvn -pl poc-db-scheduler -am spring-boot:run
mvn -pl poc-jobrunr -am spring-boot:run

mvn install                                     # build and test everything
```

Then open the Batch Console at http://localhost:8080/batch-console/. Each PoC's README lists its other endpoints.
`-am` also builds `poc-common`. Running from a module folder (`cd poc-jobrunr && mvn spring-boot:run`) works too,
after `mvn install -pl poc-common` has put `poc-common` into the local Maven repository.

**Notification mails** need a local SMTP server on port 1025; without one, the apps still work and only log the
failed delivery. Options:

```sh
brew install mailpit && mailpit           # macOS: SMTP on 1025, web UI on http://localhost:8025
docker compose -f poc-quartz-manager/compose.yaml up -d    # the same Mailpit with Docker (or docker-compose with Colima)

# no Docker, e.g. in the devcontainer: GreenMail, API and mail list on http://localhost:8099
curl -sfO https://repo1.maven.org/maven2/com/icegreen/greenmail-standalone/2.1.14/greenmail-standalone-2.1.14.jar
java -Dgreenmail.smtp.port=1025 -Dgreenmail.smtp.hostname=127.0.0.1 -Dgreenmail.auth.disabled \
     -Dgreenmail.api.port=8099 -Dgreenmail.api.hostname=127.0.0.1 -jar greenmail-standalone-2.1.14.jar
```

On macOS with Colima, `docker compose` (with a space) needs
`"cliPluginsExtraDirs": ["<output of brew --prefix>/lib/docker/cli-plugins"]` in `~/.docker/config.json`; add the
entry to the existing file rather than replacing it, which would drop the `colima` context. `mvn -v` shows the JDK
Maven uses; it must be Java 21 or newer.

### Comparison

What the PoCs showed, for the requirements that differ between the schedulers. History, logs, stop, notifications and
the UC4 API are the same in all three, because they sit on Spring Batch (`poc-common`).

| | Quartz + Quartz Manager | db-scheduler | JobRunr OSS |
|---|---|---|---|
| Change schedules at runtime | ✅ Quartz triggers, via Quartz Manager API | ✅ schedule stored as task data, `reschedule` with new data | ✅ `scheduleRecurrently` at runtime |
| Pause / resume | ✅ native | ⚠️ not native; "never" schedule in the task data | ⚠️ not in OSS; own schedule table, recurring job deleted / re-created |
| Change while a run is in progress | ✅ | ❌ refused by db-scheduler (`409`, retry after the run) | ✅ |
| Time zone per schedule | ✅ | ✅ | ✅ |
| Cron syntax | Quartz, incl. "second Tuesday", "last Friday" | Quartz (via cron-utils) | Unix-style: "last Friday" yes, "second Tuesday" no |
| Extra state besides the scheduler's tables | – | – | `batch_schedule` table |
| Pitfalls found | Quartz Manager: RAM-only scheduler, reschedule un-pauses, bare `500` for invalid cron, page refresh 404 (all worked around) | disabled cron schedule throws; running instances hidden by default; Java-serialized task data | pre-planned runs survive replacing or deleting a recurring job; an edited schedule can be ignored until the old next run (worked around with a new id per change) |
| Start latency (run now, UC4) | immediate | immediate (immediate execution enabled) | up to the poll interval (5 s minimum) |
| Same job started twice at once | queued (`@DisallowConcurrentExecution`) | runs in parallel | recurring: skipped while the previous run is pending; manual: parallel |
| Own UI next to the Batch Console | Quartz Manager UI (in the app) | db-scheduler-ui (in the app) | dashboard on a separate port, outside Spring Security |
| Licence | Apache-2.0 | Apache-2.0 | LGPL-3.0 |

Details and the reasoning behind each point: findings in [poc-quartz-manager](poc-quartz-manager/README.md#findings),
[poc-db-scheduler](poc-db-scheduler/README.md#findings) and [poc-jobrunr](poc-jobrunr/README.md#findings).

## References

- [Quartz Manager](https://github.com/fabioformosa/quartz-manager)
- [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui)
- [JobRunr licence](https://github.com/jobrunr/jobrunr/blob/master/License.md)
- [Spring Batch observability reference](https://docs.spring.io/spring-batch/reference/spring-batch-observability.html)
- [Spring Batch Admin (archived)](https://github.com/spring-projects/spring-batch-admin)
