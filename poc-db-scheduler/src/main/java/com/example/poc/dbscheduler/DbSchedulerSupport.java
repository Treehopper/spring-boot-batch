package com.example.poc.dbscheduler;

import com.example.poc.scheduling.CronPreview;
import com.example.poc.scheduling.SchedulerInfo;
import com.example.poc.scheduling.SchedulerInfo.Link;
import com.example.poc.scheduling.SchedulerSupport;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** db-scheduler for the shared parts: Quartz-style cron checked by db-scheduler itself, runs as one-time tasks. */
@Component
public class DbSchedulerSupport implements SchedulerSupport {

  private static final SchedulerInfo INFO = new SchedulerInfo("db-scheduler", "quartz", "console",
    List.of(new Link("db-scheduler UI", "/db-scheduler")));

  private final SchedulerClient schedulerClient;
  private final OneTimeTask<RunRequest> runTask;

  public DbSchedulerSupport(SchedulerClient schedulerClient, OneTimeTask<RunRequest> runTask) {
    this.schedulerClient = schedulerClient;
    this.runTask = runTask;
  }

  @Override
  public SchedulerInfo info() {
    return INFO;
  }

  @Override
  public CronPreview previewCron(String expression, String timeZone) {
    if (expression.trim().equals("-"))
      return CronPreview.invalid("\"-\" disables a schedule; use Pause instead");
    Schedule schedule;
    try {
      schedule = new CronSchedule(expression, ZoneId.of(timeZone), CronStyle.QUARTZ);
    }
    catch (IllegalArgumentException e) {
      return CronPreview.invalid(e.getMessage());
    }
    List<Instant> next = new ArrayList<>();
    Instant time = Instant.now();
    while (next.size() < CronPreview.PREVIEW_COUNT) {
      time = schedule.getNextExecutionTime(ExecutionComplete.simulatedSuccess(time));
      if (time.equals(Schedule.NEVER))
        break;
      next.add(time);
    }
    return CronPreview.of(next);
  }

  /** As a one-time task due now; with immediate execution enabled it starts without waiting for the next poll. */
  @Override
  public void startNow(String jobName, String requestId, String startedBy, Map<String, String> parameters) {
    schedulerClient.scheduleIfNotExists(runTask.instance(requestId, new RunRequest(jobName, requestId, startedBy, parameters)), Instant.now());
  }
}
