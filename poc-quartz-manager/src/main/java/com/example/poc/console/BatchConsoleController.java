package com.example.poc.console;

import com.example.poc.batch.BatchExecutions;
import com.example.poc.batch.ExecutionInfo;
import com.example.poc.config.PocProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.quartz.CronExpression;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backend of the batch console for what Quartz Manager can't provide: execution history, per-execution logs, a
 * graceful stop, and a cron check for the schedule editor. Schedules themselves are changed by the console directly
 * through the Quartz Manager API.
 */
@RestController
@RequestMapping("/batch-console/api")
public class BatchConsoleController {

  private static final int MAX_LIMIT = 200;

  private final BatchExecutions batchExecutions;
  private final Path logDir;

  public BatchConsoleController(BatchExecutions batchExecutions, PocProperties properties) {
    this.batchExecutions = batchExecutions;
    this.logDir = Path.of(properties.executionLogs().dir());
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
   * Checks a Quartz cron expression and previews its next fire times. Quartz Manager answers an invalid expression
   * with a bare HTTP 500, so the editor checks here before saving.
   */
  @GetMapping("/cron-preview")
  public CronPreview cronPreview(@RequestParam String expression, @RequestParam String timeZone) {
    if (!ZoneId.getAvailableZoneIds().contains(timeZone) && !timeZone.equals("UTC"))
      return CronPreview.invalid("Unknown time zone " + timeZone); // Quartz would silently fall back to GMT
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
    while (next.size() < 5 && (time = cron.getNextValidTimeAfter(time)) != null)
      next.add(time.toInstant());
    if (next.isEmpty())
      return CronPreview.invalid("The expression never fires");
    return new CronPreview(true, null, next);
  }

  public record CronPreview(boolean valid, String error, List<Instant> nextFireTimes) {

    static CronPreview invalid(String error) {
      return new CronPreview(false, error, List.of());
    }
  }

  private JobExecution require(long executionId) {
    return batchExecutions.find(executionId)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown execution " + executionId));
  }
}
