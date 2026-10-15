package com.example.poc.dbscheduler;

import com.github.kagkarlsson.scheduler.task.helper.ScheduleAndData;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Task data of one schedule: which batch job, when, and whether it's paused. db-scheduler stores it with the task
 * instance and asks it for the {@link Schedule} after every run, so changing the data changes the schedule.
 * <p>
 * Cron expressions use the Quartz syntax (cron-utils' Quartz definition), the same as the Quartz PoC. A paused
 * schedule is {@link Paused}: the instance stays in the table with an execution time that never comes, and keeps
 * its cron expression for resuming. (db-scheduler's own disabled cron schedule, {@code "-"}, can't be used for this:
 * it throws when the next execution time is computed, which rescheduling does.)
 */
public record BatchSchedule(String jobName, String cronExpression, String timeZone, boolean paused)
  implements ScheduleAndData {

  @Override
  public Schedule getSchedule() {
    return paused ? new Paused() : new CronSchedule(cronExpression, ZoneId.of(timeZone), CronStyle.QUARTZ);
  }

  /** Never fires: the next execution time is db-scheduler's {@link Schedule#NEVER}. */
  public record Paused() implements Schedule, Serializable {

    @Override
    public Instant getNextExecutionTime(ExecutionComplete executionComplete) {
      return NEVER;
    }

    @Override
    public boolean isDeterministic() {
      return true;
    }
  }

  /** The payload for the task; returning the record itself would be a self-reference (e.g. for db-scheduler-ui's JSON). */
  @Override
  public Object getData() {
    return jobName;
  }

  BatchSchedule withCron(String cronExpression, String timeZone) {
    return new BatchSchedule(jobName, cronExpression, timeZone, paused);
  }

  BatchSchedule withPaused(boolean paused) {
    return new BatchSchedule(jobName, cronExpression, timeZone, paused);
  }
}
