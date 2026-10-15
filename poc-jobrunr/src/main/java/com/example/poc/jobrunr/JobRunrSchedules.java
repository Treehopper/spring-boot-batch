package com.example.poc.jobrunr;

import com.example.poc.batch.BatchJobsConfig;
import com.example.poc.scheduling.CronPreview;
import com.example.poc.scheduling.ManagedSchedules;
import com.example.poc.scheduling.ScheduleView;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.Paging;
import org.jobrunr.storage.StorageProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runtime-editable schedules on JobRunr. JobRunr OSS can create, replace and delete recurring jobs at runtime, but
 * can't pause them. So the {@code batch_schedule} table is the source of truth (cron, time zone, paused), and every
 * change is synced to JobRunr: an active schedule is a recurring job with the same id, a paused one has none.
 * <p>
 * Two JobRunr behaviours make replacing a recurring job under the same id unreliable:
 * <ul>
 *   <li>JobRunr creates the next run ahead of time, as a SCHEDULED job. Replacing or deleting the recurring job
 *   leaves that run in place, so it still fires at the old time, and new runs are skipped while it is pending.</li>
 *   <li>The background server remembers the last planned run per recurring job id in memory, and plans nothing for
 *   that id until that time has passed. With a daily schedule changed to "every 5 seconds", nothing runs until the
 *   old daily run time (or a restart).</li>
 * </ul>
 * So every change creates the recurring job under a new id, {@code <schedule id>.v<version>}, and deletes the
 * previous recurring jobs of the schedule together with their pending (not yet running) runs.
 */
@Component
public class JobRunrSchedules implements ManagedSchedules, ApplicationRunner {

  private final JdbcClient jdbc;
  private final JobScheduler jobScheduler;
  private final StorageProvider storageProvider;
  private final JobRunrBatchJobs jobs;
  private final JobRunrSupport support;

  public JobRunrSchedules(JdbcClient jdbc, JobScheduler jobScheduler, StorageProvider storageProvider,
                          JobRunrBatchJobs jobs, JobRunrSupport support) {
    this.jdbc = jdbc;
    this.jobScheduler = jobScheduler;
    this.storageProvider = storageProvider;
    this.jobs = jobs;
    this.support = support;
  }

  private record Row(String id, String jobName, String cronExpression, String timeZone, boolean paused, int version) {

    String recurringJobId() {
      return id + ".v" + version;
    }

    /** this schedule's recurring jobs: all versions, and the unversioned id of earlier PoC versions */
    boolean owns(String recurringJobId) {
      return recurringJobId.equals(id) || recurringJobId.startsWith(id + ".v");
    }
  }

  /** Seeds the demo schedules on the first start, then brings JobRunr in line with the table. */
  @Override
  public void run(ApplicationArguments args) {
    seed(BatchJobsConfig.IMPORT_CUSTOMERS_JOB + "-cron", BatchJobsConfig.IMPORT_CUSTOMERS_JOB, "0 0/2 * * * *", "Europe/Vienna");
    seed(BatchJobsConfig.CLEANUP_JOB + "-cron", BatchJobsConfig.CLEANUP_JOB, "0 30 2 * * *", "America/New_York");
    rows().forEach(this::sync);
  }

  private void seed(String id, String jobName, String cron, String timeZone) {
    jdbc.sql("""
        insert into batch_schedule (id, job_name, cron_expression, time_zone, paused)
        select :id, :jobName, :cron, :timeZone, false where not exists (select 1 from batch_schedule where id = :id)
        """)
      .param("id", id).param("jobName", jobName).param("cron", cron).param("timeZone", timeZone)
      .update();
  }

  @Override
  public List<ScheduleView> schedules() {
    Map<String, RecurringJob> recurringJobs = storageProvider.getRecurringJobs().stream()
      .collect(Collectors.toMap(RecurringJob::getId, Function.identity()));
    return rows().stream().map(row -> toView(row, recurringJobs.get(row.recurringJobId()))).toList();
  }

  @Override
  @Transactional
  public ScheduleView reschedule(String id, String cronExpression, String timeZone) {
    if (!CronPreview.isKnownZone(timeZone))
      throw new IllegalArgumentException("Unknown time zone " + timeZone);
    CronPreview preview = support.previewCron(cronExpression, timeZone);
    if (!preview.valid())
      throw new IllegalArgumentException(preview.error());
    require(id);
    jdbc.sql("update batch_schedule set cron_expression = :cron, time_zone = :timeZone, version = version + 1 where id = :id")
      .param("cron", cronExpression).param("timeZone", timeZone).param("id", id)
      .update();
    return syncAndView(id);
  }

  @Override
  @Transactional
  public ScheduleView pause(String id) {
    return setPaused(id, true);
  }

  @Override
  @Transactional
  public ScheduleView resume(String id) {
    return setPaused(id, false);
  }

  private ScheduleView setPaused(String id, boolean paused) {
    require(id);
    jdbc.sql("update batch_schedule set paused = :paused, version = version + 1 where id = :id")
      .param("paused", paused).param("id", id)
      .update();
    return syncAndView(id);
  }

  private ScheduleView syncAndView(String id) {
    sync(require(id));
    return schedules().stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow();
  }

  /**
   * Removes the schedule's outdated recurring jobs and their pending runs; if active, makes sure the recurring job of
   * the current version exists. Idempotent, also used on startup.
   */
  private void sync(Row row) {
    String current = row.paused() ? null : row.recurringJobId();
    storageProvider.getRecurringJobs().stream()
      .map(RecurringJob::getId)
      .filter(row::owns)
      .filter(recurringJobId -> !recurringJobId.equals(current))
      .forEach(recurringJobId -> {
        jobScheduler.deleteRecurringJob(recurringJobId);
        deletePendingRuns(recurringJobId);
      });
    if (current == null)
      return;
    String jobName = row.jobName();
    String scheduleId = row.id();
    jobScheduler.scheduleRecurrently(current, row.cronExpression(), ZoneId.of(row.timeZone()),
      () -> jobs.runScheduled(jobName, scheduleId));
  }

  private void deletePendingRuns(String recurringJobId) {
    for (StateName state : PENDING) {
      storageProvider.getJobList(state, Paging.AmountBasedList.ascOnUpdatedAt(1000)).stream()
        .filter(job -> job.getRecurringJobId().filter(recurringJobId::equals).isPresent())
        .forEach(job -> jobScheduler.delete(job.getId(), "Schedule changed or paused"));
    }
  }

  private static final List<StateName> PENDING = List.of(StateName.AWAITING, StateName.SCHEDULED, StateName.ENQUEUED);

  private List<Row> rows() {
    return jdbc.sql("select id, job_name, cron_expression, time_zone, paused, version from batch_schedule order by job_name, id")
      .query(Row.class)
      .list();
  }

  private Row require(String id) {
    return jdbc.sql("select id, job_name, cron_expression, time_zone, paused, version from batch_schedule where id = :id")
      .param("id", id)
      .query(Row.class)
      .optional()
      .orElseThrow(() -> new ScheduleNotFoundException(id));
  }

  private static ScheduleView toView(Row row, RecurringJob recurringJob) {
    Instant nextRun = row.paused() || recurringJob == null ? null : recurringJob.getNextRun();
    return new ScheduleView(row.id(), row.jobName(), row.cronExpression(), row.timeZone(),
      row.paused() ? ScheduleView.PAUSED : ScheduleView.ACTIVE, nextRun);
  }
}
