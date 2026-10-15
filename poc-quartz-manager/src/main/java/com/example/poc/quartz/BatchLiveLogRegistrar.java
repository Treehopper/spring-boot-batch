package com.example.poc.quartz;

import java.util.List;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.item.ChunkOrientedStep;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Registers {@link BatchLiveLog} on every job and on each of its top-level steps, so that job definitions don't
 * have to list it. Steps that aren't Spring beans (e.g. built inside a job bean method) are covered as well.
 * Runs after all singletons are created, i.e. before the Quartz scheduler starts firing.
 * <p>
 * Chunk events are only produced by chunk-oriented steps built with {@code StepBuilder.chunk(int)}. Steps nested
 * in partitions or job steps are not covered.
 */
@Component
public class BatchLiveLogRegistrar implements SmartInitializingSingleton {

  private final List<Job> jobs;
  private final BatchLiveLog liveLog;

  public BatchLiveLogRegistrar(List<Job> jobs, BatchLiveLog liveLog) {
    this.jobs = jobs;
    this.liveLog = liveLog;
  }

  @Override
  public void afterSingletonsInstantiated() {
    for (Job job : jobs) {
      if (!(job instanceof AbstractJob abstractJob))
        continue;
      abstractJob.registerJobExecutionListener(liveLog);
      for (String stepName : abstractJob.getStepNames())
        register(abstractJob.getStep(stepName));
    }
  }

  private void register(Step step) {
    if (step instanceof AbstractStep abstractStep)
      abstractStep.registerStepExecutionListener(liveLog);
    if (step instanceof ChunkOrientedStep<?, ?> chunkStep)
      registerChunkListener(chunkStep);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void registerChunkListener(ChunkOrientedStep chunkStep) {
    chunkStep.registerChunkListener(liveLog);
  }
}
