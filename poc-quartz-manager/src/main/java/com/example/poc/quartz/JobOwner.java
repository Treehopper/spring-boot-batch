package com.example.poc.quartz;

import org.quartz.JobDataMap;

/** Who schedules a job: the embedded Quartz scheduler or the external UC4 scheduler. */
public enum JobOwner {
  INTERNAL, UC4;

  static JobOwner of(JobDataMap data) {
    return data.containsKey(QuartzJobs.OWNER) ? valueOf(data.getString(QuartzJobs.OWNER)) : INTERNAL;
  }
}
