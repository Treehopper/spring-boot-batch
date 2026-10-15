package com.example.poc.launch;

import org.slf4j.MDC;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Publishes the {@code jobExecutionId} for a pending start request and puts it into the MDC, so that log lines
 * of a run can be correlated with its execution.
 */
@Component
public class LaunchTrackingListener implements JobExecutionListener {

  private final PendingLaunches pendingLaunches;

  public LaunchTrackingListener(PendingLaunches pendingLaunches) {
    this.pendingLaunches = pendingLaunches;
  }

  @Override
  public void beforeJob(JobExecution jobExecution) {
    MDC.put("jobExecutionId", String.valueOf(jobExecution.getId()));
    String requestId = jobExecution.getJobParameters().getString(BatchJobStarter.REQUEST_ID);
    if (requestId != null)
      pendingLaunches.started(requestId, jobExecution.getId());
  }

  @Override
  public void afterJob(JobExecution jobExecution) {
    MDC.remove("jobExecutionId");
  }
}
