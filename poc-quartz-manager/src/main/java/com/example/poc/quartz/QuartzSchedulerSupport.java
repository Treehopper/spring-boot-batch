package com.example.poc.quartz;

import com.example.poc.scheduling.CronPreview;
import com.example.poc.scheduling.SchedulerInfo;
import com.example.poc.scheduling.SchedulerInfo.Link;
import com.example.poc.scheduling.SchedulerSupport;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.quartz.CronExpression;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Component;

/**
 * Quartz for the shared parts. The batch console reads and changes schedules through the Quartz Manager API
 * ({@code scheduleApi = quartz-manager}), so there is no {@code ManagedSchedules} here.
 */
@Component
public class QuartzSchedulerSupport implements SchedulerSupport {

  private static final SchedulerInfo INFO = new SchedulerInfo("Quartz + Quartz Manager", "quartz", "quartz-manager",
    List.of(new Link("Quartz Manager UI", "/quartz-manager-ui/")));

  private final QuartzJobs quartzJobs;

  public QuartzSchedulerSupport(QuartzJobs quartzJobs) {
    this.quartzJobs = quartzJobs;
  }

  @Override
  public SchedulerInfo info() {
    return INFO;
  }

  /** Quartz Manager answers an invalid expression with a bare HTTP 500, so the editor checks here before saving. */
  @Override
  public CronPreview previewCron(String expression, String timeZone) {
    CronExpression cron;
    try {
      cron = new CronExpression(expression);
    }
    catch (ParseException e) {
      return CronPreview.invalid(e.getMessage());
    }
    cron.setTimeZone(TimeZone.getTimeZone(timeZone));
    List<Instant> next = new ArrayList<>();
    Date time = new Date();
    while (next.size() < CronPreview.PREVIEW_COUNT && (time = cron.getNextValidTimeAfter(time)) != null)
      next.add(time.toInstant());
    return CronPreview.of(next);
  }

  /** Through Quartz ({@code Scheduler.triggerJob}), so the run passes the same wrapper as scheduled runs. */
  @Override
  public void startNow(String jobName, String requestId, String startedBy, Map<String, String> parameters) {
    try {
      quartzJobs.triggerNow(jobName, requestId, startedBy, parameters);
    }
    catch (SchedulerException e) {
      throw new IllegalStateException("Could not trigger " + jobName, e);
    }
  }
}
