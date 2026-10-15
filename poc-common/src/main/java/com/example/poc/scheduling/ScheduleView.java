package com.example.poc.scheduling;

import java.time.Instant;

/**
 * A schedule as shown in the batch console.
 *
 * @param state {@code ACTIVE}, {@code PAUSED} or {@code RUNNING} (a run of this schedule is in progress)
 */
public record ScheduleView(String id, String jobName, String cronExpression, String timeZone, String state,
                           Instant nextRun) {

  public static final String ACTIVE = "ACTIVE";
  public static final String PAUSED = "PAUSED";
  public static final String RUNNING = "RUNNING";
}
