package com.example.poc.scheduling;

import java.util.List;

/**
 * Tells the batch console which scheduler it talks to.
 *
 * @param name        shown in the console header
 * @param cronDialect syntax the recurrence builder writes: {@code quartz} or {@code jobrunr}
 * @param scheduleApi where the console reads and changes schedules: {@code quartz-manager} (the Quartz Manager API)
 *                    or {@code console} (the neutral {@code /batch-console/api/schedules} API, see
 *                    {@link ManagedSchedules})
 * @param links       the scheduler's own UI, shown next to the console
 */
public record SchedulerInfo(String name, String cronDialect, String scheduleApi, List<Link> links) {

  public record Link(String label, String url) {
  }
}
