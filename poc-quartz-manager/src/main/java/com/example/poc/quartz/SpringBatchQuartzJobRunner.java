package com.example.poc.quartz;

import com.example.poc.launch.BatchJobStarter;
import com.example.poc.launch.PendingLaunches;
import it.fabioformosa.quartzmanager.api.jobs.entities.LogRecord;
import it.fabioformosa.quartzmanager.api.jobs.entities.LogRecord.LogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/**
 * Shared logic of the Quartz jobs in {@code com.example.poc.quartz.jobs}: starts a Spring Batch job via
 * {@link BatchJobStarter} and turns the result into a Quartz Manager {@link LogRecord}. While the run is in progress,
 * {@link BatchLiveLog} streams its Spring Batch events to the same log panel.
 * <p>
 * A bean rather than an abstract base class: Quartz Manager offers every subtype of
 * {@code AbstractQuartzManagerJob} it finds as a job class in the UI, including abstract intermediate classes.
 */
@Component
public class SpringBatchQuartzJobRunner {

  private static final Logger log = LoggerFactory.getLogger(SpringBatchQuartzJobRunner.class);

  private final BatchJobStarter batchJobStarter;
  private final PendingLaunches pendingLaunches;
  private final BatchLiveLog liveLog;

  public SpringBatchQuartzJobRunner(BatchJobStarter batchJobStarter, PendingLaunches pendingLaunches, BatchLiveLog liveLog) {
    this.batchJobStarter = batchJobStarter;
    this.pendingLaunches = pendingLaunches;
    this.liveLog = liveLog;
  }

  public LogRecord run(String batchJobName, JobExecutionContext context) {
    JobDataMap data = context.getMergedJobDataMap();
    TriggerKey triggerKey = context.getTrigger().getKey();
    String requestId = data.containsKey(BatchJobStarter.REQUEST_ID)
      ? data.getString(BatchJobStarter.REQUEST_ID)
      : UUID.randomUUID().toString();
    String startedBy = data.containsKey(BatchJobStarter.STARTED_BY)
      ? data.getString(BatchJobStarter.STARTED_BY)
      : (isManualTrigger(triggerKey) ? BatchJobStarter.STARTED_BY_MANUAL : BatchJobStarter.STARTED_BY_SCHEDULE + triggerKey);

    List<String> otherTriggers = otherTriggersOfJob(context);
    LogRecord logRecord;
    if (isScheduledRun(data, triggerKey) && JobOwner.of(data) == JobOwner.UC4) {
      logRecord = new LogRecord(LogType.WARN, batchJobName + " skipped: scheduling is owned by UC4 (trigger " + triggerKey + ")");
    }
    else {
      List<String> allTriggers = new ArrayList<>(otherTriggers);
      allTriggers.add(triggerKey.getName());
      liveLog.open(requestId, allTriggers);
      try {
        logRecord = start(batchJobName, requestId, startedBy, batchParameters(data));
      }
      finally {
        liveLog.close(requestId);
      }
    }
    // AbstractQuartzManagerJob sends the returned record to the firing trigger only
    liveLog.send(otherTriggers, logRecord);
    return logRecord;
  }

  private LogRecord start(String batchJobName, String requestId, String startedBy, Map<String, String> parameters) {
    try {
      JobExecution execution = batchJobStarter.start(batchJobName, requestId, startedBy, parameters);
      return new LogRecord(execution.getStatus().isUnsuccessful() ? LogType.ERROR : LogType.INFO, summary(execution));
    }
    catch (Exception e) {
      log.error("Could not start {}", batchJobName, e);
      pendingLaunches.failed(requestId, e);
      return new LogRecord(LogType.ERROR, batchJobName + " could not be started: " + e.getMessage());
    }
  }

  private static String summary(JobExecution execution) {
    Duration duration = execution.getStartTime() != null && execution.getEndTime() != null
      ? Duration.between(execution.getStartTime(), execution.getEndTime())
      : Duration.ZERO;
    StringBuilder sb = new StringBuilder()
      .append(execution.getJobInstance().getJobName())
      .append(" #").append(execution.getId())
      .append(' ').append(execution.getStatus())
      .append(" in ").append(duration.toMillis()).append(" ms")
      .append(" (startedBy=").append(execution.getJobParameters().getString(BatchJobStarter.STARTED_BY)).append(')');
    for (StepExecution step : execution.getStepExecutions())
      sb.append(" | ").append(step.getStepName())
        .append(": read=").append(step.getReadCount())
        .append(", write=").append(step.getWriteCount())
        .append(", skip=").append(step.getSkipCount());
    execution.getAllFailureExceptions().stream().findFirst()
      .ifPresent(e -> sb.append(" | ").append(BatchLiveLog.rootCause(e)));
    return sb.toString();
  }

  /** Every Quartz job data entry except the internal ones becomes a (non-identifying) Spring Batch job parameter. */
  private static Map<String, String> batchParameters(JobDataMap data) {
    Map<String, String> parameters = new HashMap<>();
    data.forEach((key, value) -> {
      if (!key.equals(QuartzJobs.OWNER) && !key.equals(BatchJobStarter.REQUEST_ID) && !key.equals(BatchJobStarter.STARTED_BY))
        parameters.put(key, String.valueOf(value));
    });
    return parameters;
  }

  /** {@code Scheduler.triggerJob(...)} (Quartz Manager "trigger now", UC4) creates one-off triggers named {@code MT_*}. */
  private static boolean isManualTrigger(TriggerKey triggerKey) {
    return Scheduler.DEFAULT_GROUP.equals(triggerKey.getGroup()) && triggerKey.getName().startsWith("MT_");
  }

  private static boolean isScheduledRun(JobDataMap data, TriggerKey triggerKey) {
    return !data.containsKey(BatchJobStarter.REQUEST_ID) && !isManualTrigger(triggerKey);
  }

  /**
   * Quartz Manager only streams the log record to the topic of the trigger that fired. The UI subscribes to the
   * trigger selected in the UI, so runs from one-off triggers ("trigger now", UC4) would never show up. Workaround:
   * also publish the records to every other trigger of the same job.
   */
  private static List<String> otherTriggersOfJob(JobExecutionContext context) {
    try {
      return context.getScheduler().getTriggersOfJob(context.getJobDetail().getKey()).stream()
        .map(Trigger::getKey)
        .filter(key -> !key.equals(context.getTrigger().getKey()))
        .map(TriggerKey::getName)
        .toList();
    }
    catch (SchedulerException e) {
      log.warn("Could not look up the other triggers of {}", context.getJobDetail().getKey(), e);
      return List.of();
    }
  }
}
