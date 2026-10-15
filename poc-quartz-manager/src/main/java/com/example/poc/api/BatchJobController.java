package com.example.poc.api;

import com.example.poc.launch.BatchJobStarter;
import com.example.poc.quartz.JobOwner;
import com.example.poc.quartz.QuartzJobs;
import com.example.poc.quartz.QuartzJobs.TriggerInfo;
import java.util.List;
import org.quartz.SchedulerException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Quartz-specific part of the external-scheduler (UC4) API: who owns a job's scheduling. Start, status and stop
 * are shared by all PoCs, see {@code ExternalSchedulerController} in poc-common.
 */
@RestController
@RequestMapping("/api")
public class BatchJobController {

  private final QuartzJobs quartzJobs;
  private final BatchJobStarter batchJobStarter;

  public BatchJobController(QuartzJobs quartzJobs, BatchJobStarter batchJobStarter) {
    this.quartzJobs = quartzJobs;
    this.batchJobStarter = batchJobStarter;
  }

  @GetMapping("/jobs/{jobName}")
  public JobInfo job(@PathVariable String jobName) throws SchedulerException {
    requireJob(jobName);
    return new JobInfo(jobName, quartzJobs.owner(jobName), quartzJobs.triggers(jobName));
  }

  @PutMapping("/jobs/{jobName}/owner")
  public JobInfo changeOwner(@PathVariable String jobName, @RequestBody OwnerChange change) throws SchedulerException {
    requireJob(jobName);
    quartzJobs.changeOwner(jobName, change.owner());
    return job(jobName);
  }

  private void requireJob(String jobName) {
    if (!batchJobStarter.exists(jobName))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job " + jobName);
  }

  public record OwnerChange(JobOwner owner) {
  }

  public record JobInfo(String jobName, JobOwner owner, List<TriggerInfo> triggers) {
  }
}
