package com.example.poc.jobrunr;

import com.example.poc.launch.BatchJobStarter;
import java.util.Map;
import java.util.UUID;
import org.jobrunr.jobs.annotations.Job;
import org.springframework.stereotype.Component;

/**
 * The methods JobRunr jobs call. JobRunr stores a job as "class + method + arguments" and looks this bean up in the
 * Spring context when the job runs. Both only call {@link BatchJobStarter}, which never throws for them, so a failed
 * batch run doesn't trigger JobRunr's retries.
 */
@Component
public class JobRunrBatchJobs {

  private final BatchJobStarter batchJobStarter;

  public JobRunrBatchJobs(BatchJobStarter batchJobStarter) {
    this.batchJobStarter = batchJobStarter;
  }

  /** A run of a schedule (recurring job). */
  @Job(name = "%0 (schedule %1)", retries = 0)
  public void runScheduled(String jobName, String scheduleId) {
    batchJobStarter.runSafely(jobName, UUID.randomUUID().toString(), BatchJobStarter.STARTED_BY_SCHEDULE + scheduleId, Map.of());
  }

  /** A one-off run: run now, UC4 start. */
  @Job(name = "%0 (%2)", retries = 0)
  public void run(String jobName, String requestId, String startedBy, Map<String, String> parameters) {
    batchJobStarter.runSafely(jobName, requestId, startedBy, parameters);
  }
}
