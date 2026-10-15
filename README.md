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
  - [Excluded](#excluded)
- [Assessment](#assessment)
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
