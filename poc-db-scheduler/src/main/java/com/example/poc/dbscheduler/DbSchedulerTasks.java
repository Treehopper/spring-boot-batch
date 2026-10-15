package com.example.poc.dbscheduler;

import com.example.poc.launch.BatchJobStarter;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two db-scheduler tasks. Both only call {@link BatchJobStarter}, which never throws for them, so a failed batch
 * run doesn't trigger db-scheduler's retry handling.
 */
@Configuration
public class DbSchedulerTasks {

  /** One task instance per schedule; the instance id is the schedule id, the data holds cron, zone and paused flag. */
  public static final String SCHEDULE_TASK = "batch-schedule";
  /** One-off runs: run now, UC4 start. */
  public static final String RUN_TASK = "batch-run";

  @Bean
  RecurringTaskWithPersistentSchedule<BatchSchedule> scheduleTask(BatchJobStarter batchJobStarter) {
    return Tasks.recurringWithPersistentSchedule(SCHEDULE_TASK, BatchSchedule.class)
      .execute((instance, context) -> batchJobStarter.runSafely(instance.getData().jobName(),
        UUID.randomUUID().toString(), BatchJobStarter.STARTED_BY_SCHEDULE + instance.getId(), Map.of()));
  }

  @Bean
  OneTimeTask<RunRequest> runTask(BatchJobStarter batchJobStarter) {
    return Tasks.oneTime(RUN_TASK, RunRequest.class)
      .execute((instance, context) -> {
        RunRequest request = instance.getData();
        batchJobStarter.runSafely(request.jobName(), request.requestId(), request.startedBy(), request.parameters());
      });
  }
}
