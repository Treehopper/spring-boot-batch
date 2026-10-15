package com.example.poc.api;

import com.example.poc.config.PocProperties;
import com.example.poc.launch.BatchJobStarter;
import com.example.poc.launch.PendingLaunches;
import com.example.poc.quartz.JobOwner;
import com.example.poc.quartz.QuartzJobs;
import com.example.poc.quartz.QuartzJobs.TriggerInfo;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.quartz.SchedulerException;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API for an external scheduler (UC4). Starts go through Quartz, status and stop go through Spring Batch.
 */
@RestController
@RequestMapping("/api")
public class BatchJobController {

  private final QuartzJobs quartzJobs;
  private final BatchJobStarter batchJobStarter;
  private final PendingLaunches pendingLaunches;
  private final JobRepository jobRepository;
  private final JobOperator jobOperator;
  private final Duration startTimeout;

  public BatchJobController(QuartzJobs quartzJobs, BatchJobStarter batchJobStarter, PendingLaunches pendingLaunches,
                            JobRepository jobRepository, JobOperator jobOperator, PocProperties properties) {
    this.quartzJobs = quartzJobs;
    this.batchJobStarter = batchJobStarter;
    this.pendingLaunches = pendingLaunches;
    this.jobRepository = jobRepository;
    this.jobOperator = jobOperator;
    this.startTimeout = properties.api().startTimeout();
  }

  @GetMapping("/jobs/{jobName}")
  public JobInfo job(@PathVariable String jobName) throws SchedulerException {
    requireJob(jobName);
    return new JobInfo(jobName, quartzJobs.owner(jobName), quartzJobs.triggers(jobName));
  }

  /**
   * Starts a job via Quartz and waits briefly for Spring Batch to create the execution. Answers 202 with the
   * {@code jobExecutionId}, or without it if the run is queued (e.g. a previous run of the job is still active).
   */
  @PostMapping("/jobs/{jobName}/start")
  public ResponseEntity<StartResponse> start(@PathVariable String jobName,
                                             @RequestBody(required = false) Map<String, String> parameters) throws Exception {
    requireJob(jobName);
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<Long> launched = pendingLaunches.register(requestId);
    quartzJobs.triggerNow(jobName, requestId, "api", parameters == null ? Map.of() : parameters);
    return ResponseEntity.accepted().body(awaitStart(requestId, launched));
  }

  @GetMapping("/requests/{requestId}")
  public StartResponse request(@PathVariable String requestId) throws Exception {
    CompletableFuture<Long> launched = pendingLaunches.find(requestId)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown request " + requestId));
    return awaitStart(requestId, launched);
  }

  private StartResponse awaitStart(String requestId, CompletableFuture<Long> launched) throws InterruptedException {
    try {
      Long executionId = launched.get(startTimeout.toMillis(), TimeUnit.MILLISECONDS);
      return new StartResponse(requestId, executionId, URI.create("/api/executions/" + executionId));
    }
    catch (TimeoutException e) {
      return new StartResponse(requestId, null, URI.create("/api/requests/" + requestId));
    }
    catch (ExecutionException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Start failed: " + e.getCause().getMessage(), e.getCause());
    }
  }

  @GetMapping("/executions/{executionId}")
  public ExecutionInfo execution(@PathVariable long executionId) {
    return ExecutionInfo.of(requireExecution(executionId));
  }

  /** Graceful Spring Batch stop: the step finishes its current chunk, then the execution ends as STOPPED. */
  @PostMapping("/executions/{executionId}/stop")
  public ResponseEntity<ExecutionInfo> stop(@PathVariable long executionId) throws Exception {
    JobExecution execution = requireExecution(executionId);
    try {
      jobOperator.stop(execution);
    }
    catch (JobExecutionNotRunningException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    return ResponseEntity.accepted().body(ExecutionInfo.of(requireExecution(executionId)));
  }

  @PutMapping("/jobs/{jobName}/owner")
  public JobInfo changeOwner(@PathVariable String jobName, @RequestBody OwnerChange change) throws SchedulerException {
    requireJob(jobName);
    quartzJobs.changeOwner(jobName, change.owner());
    return job(jobName);
  }

  private void requireJob(String jobName) {
    if (!batchJobStarter.exists(jobName))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job " + jobName);
  }

  private JobExecution requireExecution(long executionId) {
    JobExecution execution;
    try {
      execution = jobRepository.getJobExecution(executionId);
    }
    catch (EmptyResultDataAccessException e) {
      // The JDBC repository of Spring Batch 6.0 throws instead of returning null
      execution = null;
    }
    if (execution == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown execution " + executionId);
    return execution;
  }

  public record OwnerChange(JobOwner owner) {
  }

  public record JobInfo(String jobName, JobOwner owner, List<TriggerInfo> triggers) {
  }

  public record StartResponse(String requestId, Long jobExecutionId, URI statusUrl) {
  }

  public record ExecutionInfo(long jobExecutionId, String jobName, String status, String exitCode, String exitDescription,
                              boolean running, LocalDateTime startTime, LocalDateTime endTime, Long durationMs,
                              Map<String, String> parameters, List<StepInfo> steps) {

    static ExecutionInfo of(JobExecution execution) {
      Long durationMs = execution.getStartTime() != null && execution.getEndTime() != null
        ? Duration.between(execution.getStartTime(), execution.getEndTime()).toMillis()
        : null;
      Map<String, String> parameters = new java.util.TreeMap<>();
      execution.getJobParameters().parameters().forEach(p -> parameters.put(p.name(), String.valueOf(p.value())));
      List<StepInfo> steps = execution.getStepExecutions().stream()
        .map(s -> new StepInfo(s.getStepName(), s.getStatus().name(), s.getReadCount(), s.getWriteCount(), s.getSkipCount(), s.getCommitCount()))
        .toList();
      return new ExecutionInfo(execution.getId(), execution.getJobInstance().getJobName(), execution.getStatus().name(),
        execution.getExitStatus().getExitCode(), execution.getExitStatus().getExitDescription(), execution.isRunning(),
        execution.getStartTime(), execution.getEndTime(), durationMs, parameters, steps);
    }
  }

  public record StepInfo(String name, String status, long readCount, long writeCount, long skipCount, long commitCount) {
  }
}
