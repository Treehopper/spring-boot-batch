package com.example.poc.scheduling;

import java.util.Map;

/**
 * What every PoC's scheduler provides to the shared parts (batch console, external-scheduler API). One
 * implementation per PoC.
 */
public interface SchedulerSupport {

  SchedulerInfo info();

  /** Checks a cron expression in the scheduler's own dialect and computes its next fire times. */
  CronPreview previewCron(String expression, String timeZone);

  /**
   * Starts a batch job right away, through the scheduler (so the run is visible there too). The run must pass
   * {@code requestId} and {@code startedBy} on to {@code BatchJobStarter}, which correlates it with the request.
   */
  void startNow(String jobName, String requestId, String startedBy, Map<String, String> parameters);
}
