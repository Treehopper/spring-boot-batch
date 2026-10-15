package com.example.poc.console;

import com.example.poc.batch.BatchExecutions;
import com.example.poc.batch.BatchJobsConfig;
import com.example.poc.batch.ExecutionInfo;
import com.example.poc.config.PocProperties;
import com.example.poc.launch.BatchJobStarter;
import com.example.poc.scheduling.CronPreview;
import com.example.poc.scheduling.SchedulerInfo;
import com.example.poc.scheduling.SchedulerSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backend of the batch console, shared by all PoCs: jobs, execution history, per-execution logs, graceful stop,
 * and the scheduler-specific parts through {@link SchedulerSupport} (run now, cron check, scheduler info).
 * Schedules are served by the PoC's scheduler: see {@link SchedulesController} or the Quartz Manager API.
 */
@RestController
@RequestMapping("/batch-console/api")
public class BatchConsoleController {

  private static final int MAX_LIMIT = 200;

  private final BatchExecutions batchExecutions;
  private final BatchJobStarter batchJobStarter;
  private final SchedulerSupport schedulerSupport;
  private final Path logDir;

  public BatchConsoleController(BatchExecutions batchExecutions, BatchJobStarter batchJobStarter,
                                SchedulerSupport schedulerSupport, PocProperties properties) {
    this.batchExecutions = batchExecutions;
    this.batchJobStarter = batchJobStarter;
    this.schedulerSupport = schedulerSupport;
    this.logDir = Path.of(properties.executionLogs().dir());
  }

  @GetMapping("/jobs")
  public List<JobInfo> jobs() {
    return batchJobStarter.jobNames().stream()
      .map(name -> new JobInfo(name, BatchJobsConfig.JOB_DESCRIPTIONS.get(name)))
      .toList();
  }

  public record JobInfo(String name, String description) {
  }

  /** Starts a job now, through the scheduler. The new execution shows up in {@code /executions} shortly after. */
  @PostMapping("/jobs/{jobName}/run")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, String> run(@PathVariable String jobName) {
    if (!batchJobStarter.exists(jobName))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job " + jobName);
    String requestId = UUID.randomUUID().toString();
    schedulerSupport.startNow(jobName, requestId, BatchJobStarter.STARTED_BY_MANUAL, Map.of());
    return Map.of("requestId", requestId);
  }

  @GetMapping("/executions")
  public List<ExecutionInfo> executions(@RequestParam(required = false) String jobName,
                                        @RequestParam(defaultValue = "50") int limit) {
    return batchExecutions.recent(jobName, Math.clamp(limit, 1, MAX_LIMIT)).stream().map(ExecutionInfo::of).toList();
  }

  @GetMapping("/executions/{executionId}")
  public ExecutionInfo execution(@PathVariable long executionId) {
    return ExecutionInfo.of(require(executionId));
  }

  /** The log lines written while the execution ran (see {@code logback-spring.xml}); empty if there are none. */
  @GetMapping(value = "/executions/{executionId}/log", produces = MediaType.TEXT_PLAIN_VALUE)
  public String log(@PathVariable long executionId) throws IOException {
    require(executionId);
    Path file = logDir.resolve(executionId + ".log");
    return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  @PostMapping("/executions/{executionId}/stop")
  public ExecutionInfo stop(@PathVariable long executionId) {
    JobExecution execution = require(executionId);
    try {
      batchExecutions.stop(execution);
    }
    catch (JobExecutionNotRunningException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    return ExecutionInfo.of(require(executionId));
  }

  /**
   * The scheduler behind this console, and the server's default time zone. Each schedule is evaluated in its own
   * time zone, so the latter is informational: the console shows the next runs in server time as well.
   */
  @GetMapping("/server")
  public ServerInfo server() {
    return new ServerInfo(ZoneId.systemDefault().getId(), schedulerSupport.info());
  }

  public record ServerInfo(String timeZone, SchedulerInfo scheduler) {
  }

  /** Checks a cron expression with the scheduler's own parser and previews its next fire times. */
  @GetMapping("/cron-preview")
  public CronPreview cronPreview(@RequestParam String expression, @RequestParam String timeZone) {
    if (!CronPreview.isKnownZone(timeZone))
      return CronPreview.invalid("Unknown time zone " + timeZone);
    return schedulerSupport.previewCron(expression, timeZone);
  }

  private JobExecution require(long executionId) {
    return batchExecutions.find(executionId)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown execution " + executionId));
  }
}
