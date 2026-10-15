package com.example.poc.jobrunr;

import com.example.poc.scheduling.CronPreview;
import com.example.poc.scheduling.SchedulerInfo;
import com.example.poc.scheduling.SchedulerInfo.Link;
import com.example.poc.scheduling.SchedulerSupport;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.scheduling.cron.CronExpression;
import org.jobrunr.scheduling.cron.InvalidCronExpressionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JobRunr for the shared parts: JobRunr's own (Unix-style) cron syntax, runs as enqueued jobs. JobRunr's cron has no
 * "nth weekday of the month" ({@code #}), so the batch console's builder offers only "last weekday" there.
 */
@Component
public class JobRunrSupport implements SchedulerSupport {

  private final JobScheduler jobScheduler;
  private final JobRunrBatchJobs jobs;
  private final SchedulerInfo info;

  public JobRunrSupport(JobScheduler jobScheduler, JobRunrBatchJobs jobs,
                        @Value("${jobrunr.dashboard.port:8000}") int dashboardPort) {
    this.jobScheduler = jobScheduler;
    this.jobs = jobs;
    this.info = new SchedulerInfo("JobRunr", "jobrunr", "console",
      List.of(new Link("JobRunr dashboard", "http://localhost:" + dashboardPort + "/dashboard")));
  }

  @Override
  public SchedulerInfo info() {
    return info;
  }

  @Override
  public CronPreview previewCron(String expression, String timeZone) {
    CronExpression cron;
    try {
      cron = new CronExpression(expression);
    }
    catch (InvalidCronExpressionException | IllegalArgumentException e) {
      return CronPreview.invalid(e.getMessage());
    }
    ZoneId zone = ZoneId.of(timeZone);
    Instant now = Instant.now();
    List<Instant> next = new ArrayList<>();
    Instant time = now;
    while (next.size() < CronPreview.PREVIEW_COUNT) {
      // JobRunr's next() may return the given instant itself if it matches, so ask from one second later
      time = cron.next(now, time.plusSeconds(1), zone);
      if (time == null)
        break;
      next.add(time);
    }
    return CronPreview.of(next);
  }

  /** As an enqueued job; a background job server picks it up within its poll interval. */
  @Override
  public void startNow(String jobName, String requestId, String startedBy, Map<String, String> parameters) {
    HashMap<String, String> arguments = new HashMap<>(parameters);
    jobScheduler.enqueue(() -> jobs.run(jobName, requestId, startedBy, arguments));
  }
}
