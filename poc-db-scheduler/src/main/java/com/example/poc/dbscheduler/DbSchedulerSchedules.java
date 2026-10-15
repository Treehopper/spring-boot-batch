package com.example.poc.dbscheduler;

import com.example.poc.batch.BatchJobsConfig;
import com.example.poc.scheduling.CronPreview;
import com.example.poc.scheduling.ManagedSchedules;
import com.example.poc.scheduling.ScheduleView;
import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.ScheduledExecutionsFilter;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runtime-editable schedules on db-scheduler: every schedule is an instance of the
 * {@link DbSchedulerTasks#SCHEDULE_TASK} task, and its {@link BatchSchedule} data is the schedule. Changing it means
 * rescheduling the instance with new data; everything stays in db-scheduler's {@code scheduled_tasks} table.
 */
@Component
public class DbSchedulerSchedules implements ManagedSchedules, ApplicationRunner {

  private final SchedulerClient schedulerClient;
  private final RecurringTaskWithPersistentSchedule<BatchSchedule> scheduleTask;
  private final DbSchedulerSupport support;

  public DbSchedulerSchedules(SchedulerClient schedulerClient, RecurringTaskWithPersistentSchedule<BatchSchedule> scheduleTask,
                              DbSchedulerSupport support) {
    this.schedulerClient = schedulerClient;
    this.scheduleTask = scheduleTask;
    this.support = support;
  }

  /** The demo schedules, created on the first start only: afterwards the stored (possibly edited) ones apply. */
  @Override
  public void run(ApplicationArguments args) {
    seed(BatchJobsConfig.IMPORT_CUSTOMERS_JOB + "-cron", BatchJobsConfig.IMPORT_CUSTOMERS_JOB, "0 0/2 * * * ?", "Europe/Vienna");
    seed(BatchJobsConfig.CLEANUP_JOB + "-cron", BatchJobsConfig.CLEANUP_JOB, "0 30 2 * * ?", "America/New_York");
  }

  private void seed(String id, String jobName, String cron, String timeZone) {
    schedulerClient.scheduleIfNotExists(scheduleTask.schedulableInstance(id, new BatchSchedule(jobName, cron, timeZone, false)));
  }

  @Override
  public List<ScheduleView> schedules() {
    List<ScheduleView> schedules = new ArrayList<>();
    // include picked (running) executions, which db-scheduler's default listing leaves out
    schedulerClient.fetchScheduledExecutionsForTask(DbSchedulerTasks.SCHEDULE_TASK, BatchSchedule.class,
      ScheduledExecutionsFilter.all(), execution -> schedules.add(toView(execution)));
    schedules.sort(Comparator.comparing(ScheduleView::jobName).thenComparing(ScheduleView::id));
    return schedules;
  }

  @Override
  public ScheduleView reschedule(String id, String cronExpression, String timeZone) {
    if (!CronPreview.isKnownZone(timeZone))
      throw new IllegalArgumentException("Unknown time zone " + timeZone);
    CronPreview preview = support.previewCron(cronExpression, timeZone);
    if (!preview.valid())
      throw new IllegalArgumentException(preview.error());
    return update(id, current(id).withCron(cronExpression, timeZone));
  }

  @Override
  public ScheduleView pause(String id) {
    return update(id, current(id).withPaused(true));
  }

  @Override
  public ScheduleView resume(String id) {
    return update(id, current(id).withPaused(false));
  }

  private BatchSchedule current(String id) {
    return (BatchSchedule) execution(id).getData();
  }

  /**
   * Replaces the instance's data and recomputes its next execution time from the new schedule. db-scheduler refuses
   * this while the instance is executing.
   */
  private ScheduleView update(String id, BatchSchedule schedule) {
    try {
      schedulerClient.reschedule(scheduleTask.schedulableInstance(id, schedule));
    }
    catch (TaskInstanceCurrentlyExecutingException e) {
      throw new ScheduleBusyException("A run of this schedule is in progress. db-scheduler can't change a schedule while it runs; try again after the run.");
    }
    return toView(execution(id));
  }

  private ScheduledExecution<Object> execution(String id) {
    return schedulerClient.getScheduledExecution(TaskInstanceId.of(DbSchedulerTasks.SCHEDULE_TASK, id))
      .orElseThrow(() -> new ScheduleNotFoundException(id));
  }

  private static ScheduleView toView(ScheduledExecution<?> execution) {
    BatchSchedule schedule = (BatchSchedule) execution.getData();
    String state = execution.isPicked() ? ScheduleView.RUNNING : schedule.paused() ? ScheduleView.PAUSED : ScheduleView.ACTIVE;
    boolean hasNextRun = !execution.isPicked() && !execution.getExecutionTime().equals(Schedule.NEVER);
    return new ScheduleView(execution.getTaskInstance().getId(), schedule.jobName(), schedule.cronExpression(),
      schedule.timeZone(), state, hasNextRun ? execution.getExecutionTime() : null);
  }
}
