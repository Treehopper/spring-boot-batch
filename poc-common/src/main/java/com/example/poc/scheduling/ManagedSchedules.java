package com.example.poc.scheduling;

import java.util.List;

/**
 * Runtime-editable schedules for schedulers that have no management API of their own (db-scheduler, JobRunr).
 * Exposed to the batch console as {@code /batch-console/api/schedules}. The Quartz PoC doesn't implement it: its
 * console uses the Quartz Manager API.
 */
public interface ManagedSchedules {

  List<ScheduleView> schedules();

  /**
   * Replaces cron expression and time zone, keeping the paused state.
   *
   * @throws ScheduleNotFoundException     for an unknown id
   * @throws IllegalArgumentException      for an invalid expression or zone
   * @throws ScheduleBusyException         if the scheduler can't change it right now
   */
  ScheduleView reschedule(String id, String cronExpression, String timeZone);

  ScheduleView pause(String id);

  ScheduleView resume(String id);

  class ScheduleNotFoundException extends RuntimeException {
    public ScheduleNotFoundException(String id) {
      super("Unknown schedule " + id);
    }
  }

  class ScheduleBusyException extends RuntimeException {
    public ScheduleBusyException(String message) {
      super(message);
    }
  }
}
