package com.example.poc.quartz;

import com.example.poc.launch.BatchJobStarter;
import it.fabioformosa.quartzmanager.api.jobs.entities.LogRecord;
import it.fabioformosa.quartzmanager.api.jobs.entities.LogRecord.LogType;
import it.fabioformosa.quartzmanager.api.websockets.WebhookSender;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Component;

/**
 * Streams Spring Batch events of a run to the Quartz Manager log panel while the run is in progress: job started,
 * step started, chunk written, chunk failed, step finished. Quartz Manager itself only shows the one record a job
 * returns at the end.
 * <p>
 * The log panel shows the records of the trigger selected in the UI, so {@link SpringBatchQuartzJobRunner} registers
 * the trigger topics of each run under its request id ({@link #open}), and the listener callbacks look them up via
 * the {@code requestId} job parameter. Registered on every job and step by {@link BatchLiveLogRegistrar}.
 * <p>
 * Live only, like everything in the Quartz Manager log panel: nothing is stored, and the UI keeps the last 50 records.
 */
@Component
public class BatchLiveLog implements JobExecutionListener, StepExecutionListener, ChunkListener<Object, Object> {

  private final WebhookSender<LogRecord> webSocketLogsNotifier;
  private final int chunkEvery;
  private final Map<String, List<String>> topicsByRequestId = new ConcurrentHashMap<>();

  public BatchLiveLog(WebhookSender<LogRecord> webSocketLogsNotifier, LiveLogProperties properties) {
    this.webSocketLogsNotifier = webSocketLogsNotifier;
    this.chunkEvery = Math.max(1, properties.chunkEvery());
  }

  /** Starts streaming events of the run with this request id to the given trigger names. */
  public void open(String requestId, List<String> triggerNames) {
    topicsByRequestId.put(requestId, List.copyOf(triggerNames));
  }

  public void close(String requestId) {
    topicsByRequestId.remove(requestId);
  }

  /** Sends a record to the log topics of the given triggers, e.g. a run's final summary. */
  public void send(Collection<String> triggerNames, LogRecord logRecord) {
    logRecord.setThreadName(Thread.currentThread().getName());
    triggerNames.forEach(triggerName -> webSocketLogsNotifier.send(triggerName, logRecord));
  }

  @Override
  public void beforeJob(JobExecution execution) {
    String parameters = execution.getJobParameters().parameters().stream()
      .filter(p -> !p.name().equals(BatchJobStarter.REQUEST_ID) && !p.name().equals(BatchJobStarter.STARTED_BY))
      .map(p -> p.name() + "=" + p.value())
      .sorted()
      .collect(Collectors.joining(", "));
    publish(execution.getJobParameters(), LogType.INFO, "%s #%d started by %s%s".formatted(
      execution.getJobInstance().getJobName(), execution.getId(),
      execution.getJobParameters().getString(BatchJobStarter.STARTED_BY),
      parameters.isEmpty() ? "" : " | parameters: " + parameters));
  }

  @Override
  public void beforeStep(StepExecution step) {
    publish(step.getJobExecution().getJobParameters(), LogType.INFO, step.getStepName() + " started");
  }

  /**
   * Called before the chunk's counts are added to the step execution and before the commit, so the totals are
   * computed from the step's previous totals plus this chunk.
   */
  @Override
  public void afterChunk(Chunk<Object> chunk) {
    StepExecution step = currentStep();
    if (step == null)
      return;
    long chunkNumber = step.getCommitCount() + 1;
    if (chunkNumber % chunkEvery != 0)
      return;
    publish(step.getJobExecution().getJobParameters(), LogType.INFO, "%s: chunk %d written, %d items (%d total)".formatted(
      step.getStepName(), chunkNumber, chunk.size(), step.getWriteCount() + chunk.size()));
  }

  @Override
  public void onChunkError(Exception exception, Chunk<Object> chunk) {
    StepExecution step = currentStep();
    if (step == null)
      return;
    publish(step.getJobExecution().getJobParameters(), LogType.ERROR, "%s: chunk %d failed and was rolled back: %s".formatted(
      step.getStepName(), step.getCommitCount() + 1, rootCause(exception)));
  }

  @Override
  public ExitStatus afterStep(StepExecution step) {
    BatchStatus status = step.getStatus();
    LogType type = status == BatchStatus.COMPLETED ? LogType.INFO : status == BatchStatus.STOPPED ? LogType.WARN : LogType.ERROR;
    long durationMs = step.getStartTime() == null ? 0 : Duration.between(step.getStartTime(), LocalDateTime.now()).toMillis();
    StringBuilder message = new StringBuilder()
      .append(step.getStepName()).append(' ').append(status).append(" in ").append(durationMs).append(" ms")
      .append(": read=").append(step.getReadCount())
      .append(", written=").append(step.getWriteCount())
      .append(", skipped=").append(step.getSkipCount())
      .append(", commits=").append(step.getCommitCount())
      .append(", rollbacks=").append(step.getRollbackCount());
    step.getFailureExceptions().stream().findFirst().ifPresent(e -> message.append(" | ").append(rootCause(e)));
    publish(step.getJobExecution().getJobParameters(), type, message.toString());
    return null;
  }

  private void publish(JobParameters parameters, LogType type, String message) {
    String requestId = parameters.getString(BatchJobStarter.REQUEST_ID);
    List<String> topics = requestId == null ? null : topicsByRequestId.get(requestId);
    if (topics != null)
      send(topics, new LogRecord(type, message));
  }

  private static StepExecution currentStep() {
    StepContext context = StepSynchronizationManager.getContext();
    return context == null ? null : context.getStepExecution();
  }

  static String rootCause(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause)
      cause = cause.getCause();
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }
}
