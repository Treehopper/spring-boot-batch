package com.example.poc.launch;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Service;

/**
 * The single entry point for starting Spring Batch jobs. Schedulers (Quartz here, UC4 later) only call this
 * service and never contain job logic themselves.
 */
@Service
public class BatchJobStarter {

  /** Identifying parameter that makes every run a new job instance and correlates external start requests. */
  public static final String REQUEST_ID = "requestId";
  /** Non-identifying parameter recording what started the run (cron trigger, manual trigger, UC4, ...). */
  public static final String STARTED_BY = "startedBy";

  private final JobOperator jobOperator;
  private final Map<String, Job> jobsByName;

  public BatchJobStarter(JobOperator jobOperator, List<Job> jobs) {
    this.jobOperator = jobOperator;
    this.jobsByName = jobs.stream().collect(Collectors.toMap(Job::getName, Function.identity()));
  }

  public boolean exists(String jobName) {
    return jobsByName.containsKey(jobName);
  }

  /**
   * Runs the job synchronously (Boot's default job operator uses a synchronous task executor), so the calling
   * Quartz worker thread stays busy for the whole run.
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
