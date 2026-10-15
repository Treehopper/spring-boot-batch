package com.example.poc.batch;

import com.example.poc.launch.BatchJobStarter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;

/**
 * A Spring Batch job execution as returned by the REST APIs. Timestamps carry the server's offset, because Spring
 * Batch stores them as local date-times.
 */
public record ExecutionInfo(long jobExecutionId, String jobName, String status, String exitCode, String exitDescription,
                            boolean running, String startedBy, OffsetDateTime startTime, OffsetDateTime endTime,
                            Long durationMs, Map<String, String> parameters, List<StepInfo> steps) {

  public static ExecutionInfo of(JobExecution execution) {
    Map<String, String> parameters = new TreeMap<>();
    execution.getJobParameters().parameters().forEach(p -> parameters.put(p.name(), String.valueOf(p.value())));
    List<StepInfo> steps = execution.getStepExecutions().stream().map(StepInfo::of).toList();
    return new ExecutionInfo(execution.getId(), execution.getJobInstance().getJobName(), execution.getStatus().name(),
      execution.getExitStatus().getExitCode(), execution.getExitStatus().getExitDescription(), execution.isRunning(),
      execution.getJobParameters().getString(BatchJobStarter.STARTED_BY),
      withOffset(execution.getStartTime()), withOffset(execution.getEndTime()),
      durationMs(execution.getStartTime(), execution.getEndTime()), parameters, steps);
  }

  public record StepInfo(String name, String status, long readCount, long writeCount, long skipCount, long commitCount,
                         long rollbackCount, Long durationMs) {

    static StepInfo of(StepExecution step) {
      return new StepInfo(step.getStepName(), step.getStatus().name(), step.getReadCount(), step.getWriteCount(),
        step.getSkipCount(), step.getCommitCount(), step.getRollbackCount(), ExecutionInfo.durationMs(step.getStartTime(), step.getEndTime()));
    }
  }

  private static OffsetDateTime withOffset(LocalDateTime time) {
    return time == null ? null : time.atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  private static Long durationMs(LocalDateTime start, LocalDateTime end) {
    return start != null && end != null ? Duration.between(start, end).toMillis() : null;
  }
}
