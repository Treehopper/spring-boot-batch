package com.example.poc.api;

import com.example.poc.batch.BatchExecutions;
import com.example.poc.batch.ExecutionInfo;
import com.example.poc.config.PocProperties;
import com.example.poc.launch.BatchJobStarter;
import com.example.poc.launch.PendingLaunches;
import com.example.poc.scheduling.SchedulerSupport;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API for an external scheduler (UC4): start through the PoC's scheduler, poll status and stop through Spring Batch.
 * Protected with the technical user, see {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api")
public class ExternalSchedulerController {

  private final SchedulerSupport schedulerSupport;
  private final BatchJobStarter batchJobStarter;
  private final PendingLaunches pendingLaunches;
  private final BatchExecutions batchExecutions;
  private final Duration startTimeout;

  public ExternalSchedulerController(SchedulerSupport schedulerSupport, BatchJobStarter batchJobStarter,
                                     PendingLaunches pendingLaunches, BatchExecutions batchExecutions,
                                     PocProperties properties) {
    this.schedulerSupport = schedulerSupport;
    this.batchJobStarter = batchJobStarter;
    this.pendingLaunches = pendingLaunches;
    this.batchExecutions = batchExecutions;
    this.startTimeout = properties.api().startTimeout();
  }

  /**
   * Starts a job through the scheduler and waits briefly for Spring Batch to create the execution. Answers 202 with
   * the {@code jobExecutionId}, or without it if the run hasn't started yet (e.g. queued in the scheduler).
   */
  @PostMapping("/jobs/{jobName}/start")
  public ResponseEntity<StartResponse> start(@PathVariable String jobName,
                                             @RequestBody(required = false) Map<String, String> parameters) throws Exception {
    if (!batchJobStarter.exists(jobName))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job " + jobName);
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<Long> launched = pendingLaunches.register(requestId);
    schedulerSupport.startNow(jobName, requestId, BatchJobStarter.STARTED_BY_API, parameters == null ? Map.of() : parameters);
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
  public ResponseEntity<ExecutionInfo> stop(@PathVariable long executionId) {
    JobExecution execution = requireExecution(executionId);
    try {
      batchExecutions.stop(execution);
    }
    catch (JobExecutionNotRunningException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    return ResponseEntity.accepted().body(ExecutionInfo.of(requireExecution(executionId)));
  }

  private JobExecution requireExecution(long executionId) {
    return batchExecutions.find(executionId)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown execution " + executionId));
  }

  public record StartResponse(String requestId, Long jobExecutionId, URI statusUrl) {
  }
}
