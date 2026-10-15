package com.example.poc.quartz;

import com.example.poc.batch.BatchJobsConfig;
import com.example.poc.launch.BatchJobStarter;
import com.example.poc.quartz.jobs.CleanupQuartzJob;
import com.example.poc.quartz.jobs.ImportCustomersQuartzJob;
import it.fabioformosa.quartzmanager.api.jobs.AbstractQuartzManagerJob;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Registers one durable Quartz job per Spring Batch job (group {@value #GROUP}, name = batch job name) plus a
 * default cron trigger, and offers the operations needed by the external-scheduler API.
 * <p>
 * Jobs and triggers live in the JDBC job store, so they are only created if missing. Schedule changes made in
 * Quartz Manager survive restarts.
 */
@Component
public class QuartzJobs implements ApplicationRunner {

  public static final String GROUP = "batch";
  /** Quartz job data key: who owns scheduling of this job, see {@link JobOwner}. */
  public static final String OWNER = "owner";

  private static final Logger log = LoggerFactory.getLogger(QuartzJobs.class);

  private final Scheduler scheduler;

  public QuartzJobs(@Qualifier(QuartzConfig.QUARTZ_MANAGER_SCHEDULER) Scheduler scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public void run(ApplicationArguments args) throws SchedulerException {
    register(ImportCustomersQuartzJob.class, BatchJobsConfig.IMPORT_CUSTOMERS_JOB,
      "Imports customers (demo chunk job)", "0 0/2 * * * ?", "Europe/Vienna");
    register(CleanupQuartzJob.class, BatchJobsConfig.CLEANUP_JOB,
      "Nightly cleanup (demo tasklet job)", "0 30 2 * * ?", "America/New_York");
  }

  private void register(Class<? extends AbstractQuartzManagerJob> jobClass, String batchJobName, String description,
                        String cron, String timeZone) throws SchedulerException {
    JobKey jobKey = jobKey(batchJobName);
    if (scheduler.checkExists(jobKey))
      return;
    JobDetail job = JobBuilder.newJob(jobClass)
      .withIdentity(jobKey)
      .withDescription(description)
      .usingJobData(QuartzJobs.OWNER, JobOwner.INTERNAL.name())
      .storeDurably()
      .build();
    Trigger trigger = TriggerBuilder.newTrigger()
      .withIdentity(batchJobName + "-cron", GROUP)
      .forJob(job)
      .withSchedule(CronScheduleBuilder.cronSchedule(cron)
        .inTimeZone(TimeZone.getTimeZone(timeZone))
        .withMisfireHandlingInstructionDoNothing())
      .build();
    scheduler.addJob(job, false);
    scheduler.scheduleJob(trigger);
    log.info("Scheduled {} with '{}' in {}, next fire time {}", jobKey, cron, timeZone, trigger.getNextFireTime());
  }

  public static JobKey jobKey(String batchJobName) {
    return JobKey.jobKey(batchJobName, GROUP);
  }

  /**
   * Starts the job through Quartz (one-off trigger), so that the run passes through the same wrapper as scheduled
   * runs and is visible in Quartz Manager.
   */
  public void triggerNow(String batchJobName, String requestId, String startedBy, Map<String, String> parameters) throws SchedulerException {
    JobDataMap data = new JobDataMap(parameters);
    data.put(BatchJobStarter.REQUEST_ID, requestId);
    data.put(BatchJobStarter.STARTED_BY, startedBy);
    scheduler.triggerJob(jobKey(batchJobName), data);
  }

  public JobOwner owner(String batchJobName) throws SchedulerException {
    return JobOwner.of(scheduler.getJobDetail(jobKey(batchJobName)).getJobDataMap());
  }

  /** Handing a job over to UC4 pauses its internal triggers; handing it back resumes them. */
  public void changeOwner(String batchJobName, JobOwner owner) throws SchedulerException {
    JobKey jobKey = jobKey(batchJobName);
    JobDetail current = scheduler.getJobDetail(jobKey);
    JobDetail updated = current.getJobBuilder().usingJobData(QuartzJobs.OWNER, owner.name()).build();
    scheduler.addJob(updated, true);
    if (owner == JobOwner.UC4)
      scheduler.pauseJob(jobKey);
    else
      scheduler.resumeJob(jobKey);
  }

  public List<TriggerInfo> triggers(String batchJobName) throws SchedulerException {
    return scheduler.getTriggersOfJob(jobKey(batchJobName)).stream()
      .map(this::toInfo)
      .toList();
  }

  private TriggerInfo toInfo(Trigger trigger) {
    try {
      TriggerKey key = trigger.getKey();
      CronTrigger cron = trigger instanceof CronTrigger cronTrigger ? cronTrigger : null;
      return new TriggerInfo(key.getGroup() + "." + key.getName(), scheduler.getTriggerState(key).name(),
        cron == null ? null : cron.getCronExpression(), cron == null ? null : cron.getTimeZone().getID(),
        trigger.getNextFireTime() == null ? null : trigger.getNextFireTime().toInstant());
    }
    catch (SchedulerException e) {
      throw new IllegalStateException(e);
    }
  }

  public record TriggerInfo(String key, String state, String cronExpression, String timeZone, Instant nextFireTime) {
  }
}
