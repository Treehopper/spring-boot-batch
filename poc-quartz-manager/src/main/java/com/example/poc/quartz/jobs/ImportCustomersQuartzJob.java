package com.example.poc.quartz.jobs;

import com.example.poc.batch.BatchJobsConfig;
import com.example.poc.quartz.SpringBatchQuartzJobRunner;
import it.fabioformosa.quartzmanager.api.jobs.AbstractQuartzManagerJob;
import it.fabioformosa.quartzmanager.api.jobs.entities.LogRecord;
import jakarta.annotation.Resource;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

@DisallowConcurrentExecution
public class ImportCustomersQuartzJob extends AbstractQuartzManagerJob {

  @Resource
  private SpringBatchQuartzJobRunner runner;

  @Override
  public LogRecord doIt(JobExecutionContext context) {
    return runner.run(BatchJobsConfig.IMPORT_CUSTOMERS_JOB, context);
  }
}
