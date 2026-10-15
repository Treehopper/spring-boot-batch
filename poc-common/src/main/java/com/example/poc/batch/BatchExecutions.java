package com.example.poc.batch;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/** Read and stop Spring Batch executions, shared by the UC4 API and the batch console. */
@Service
public class BatchExecutions {

  private final JobRepository jobRepository;
  private final JobOperator jobOperator;

  public BatchExecutions(JobRepository jobRepository, JobOperator jobOperator) {
    this.jobRepository = jobRepository;
    this.jobOperator = jobOperator;
  }

  public Optional<JobExecution> find(long executionId) {
    try {
      return Optional.ofNullable(jobRepository.getJobExecution(executionId));
    }
    catch (EmptyResultDataAccessException e) {
      // The JDBC repository of Spring Batch 6.0 throws instead of returning null
      return Optional.empty();
    }
  }

  /**
   * The most recent executions, newest first. Every run is its own job instance (unique {@code requestId}
   * parameter), so the latest {@code limit} instances per job contain the latest {@code limit} executions.
   */
  public List<JobExecution> recent(String jobName, int limit) {
    Stream<String> jobNames = jobName == null ? jobRepository.getJobNames().stream() : Stream.of(jobName);
    return jobNames
      .flatMap(name -> jobRepository.getJobInstances(name, 0, limit).stream())
      .flatMap(instance -> jobRepository.getJobExecutions(instance).stream())
      .sorted(Comparator.comparingLong(JobExecution::getId).reversed())
      .limit(limit)
      .toList();
  }

  /** Graceful stop: the running step finishes its current chunk, then the execution ends as STOPPED. */
  public void stop(JobExecution execution) throws JobExecutionNotRunningException {
    jobOperator.stop(execution);
  }
}
