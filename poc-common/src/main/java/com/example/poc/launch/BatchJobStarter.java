package com.example.poc.launch;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Service;

/**
 * The single entry point for starting Spring Batch jobs. Schedulers (Quartz, db-scheduler, JobRunr, later UC4) only call this
 * service and never contain job logic themselves.
 */
@Service
public class BatchJobStarter {

  /** Identifying parameter that makes every run a new job instance and correlates external start requests. */
  public static final String REQUEST_ID = "requestId";
  /** Non-identifying parameter recording what started the run (schedule, manual start, UC4, ...). */
  public static final String STARTED_BY = "startedBy";
  /** {@link #STARTED_BY} for a start from a UI ("run now") */
  public static final String STARTED_BY_MANUAL = "manual";
  /** {@link #STARTED_BY} for a start through the external-scheduler API */
  public static final String STARTED_BY_API = "api";
  /** Prefix of {@link #STARTED_BY} for a run of a schedule, followed by the schedule's id */
  public static final String STARTED_BY_SCHEDULE = "schedule:";

  private static final Logger log = LoggerFactory.getLogger(BatchJobStarter.class);

  private final JobOperator jobOperator;
  private final PendingLaunches pendingLaunches;
  private final Map<String, Job> jobsByName;

  public BatchJobStarter(JobOperator jobOperator, PendingLaunches pendingLaunches, List<Job> jobs) {
    this.jobOperator = jobOperator;
    this.pendingLaunches = pendingLaunches;
    this.jobsByName = new TreeMap<>(jobs.stream().collect(Collectors.toMap(Job::getName, Function.identity())));
  }

  public boolean exists(String jobName) {
    return jobsByName.containsKey(jobName);
  }

  public Set<String> jobNames() {
    return jobsByName.keySet();
  }

  /**
   * Like {@link #start}, for scheduler tasks: never throws, so a failed start doesn't trigger the scheduler's retry
   * or failure handling. A failed batch run is reported by Spring Batch (status, notification), not by the scheduler.
   *
   * @return the finished execution, or {@code null} if the job could not be started
   */
  public JobExecution runSafely(String jobName, String requestId, String startedBy, Map<String, String> parameters) {
    try {
      return start(jobName, requestId, startedBy, parameters);
    }
    catch (Exception e) {
      log.error("Could not start {}", jobName, e);
      pendingLaunches.failed(requestId, e);
      return null;
    }
  }

  /**
   * Runs the job synchronously (Boot's default job operator uses a synchronous task executor), so the calling
   * scheduler thread stays busy for the whole run.
   */
  public JobExecution start(String jobName, String requestId, String startedBy, Map<String, String> parameters) throws Exception {
    Job job = jobsByName.get(jobName);
    if (job == null)
      throw new IllegalArgumentException("Unknown batch job " + jobName);
    JobParametersBuilder builder = new JobParametersBuilder()
      .addString(REQUEST_ID, requestId)
      .addString(STARTED_BY, startedBy, false);
    parameters.forEach((key, value) -> builder.addString(key, value, false));
    return jobOperator.start(job, builder.toJobParameters());
  }
}
