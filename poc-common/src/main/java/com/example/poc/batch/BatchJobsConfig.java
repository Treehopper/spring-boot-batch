package com.example.poc.batch;

import com.example.poc.launch.LaunchTrackingListener;
import com.example.poc.notification.JobNotificationListener;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Two demo jobs. Behaviour is controlled by job parameters so that slow, failing and stoppable runs can be
 * produced on demand:
 * <ul>
 *   <li>{@code itemCount} – number of items to process (default 20)</li>
 *   <li>{@code itemDelayMs} – sleep per item, to make runs long enough to stop (default 200)</li>
 *   <li>{@code failAtItem} – throw on this item, to test failure notifications (default: never)</li>
 * </ul>
 */
@Configuration
public class BatchJobsConfig {

  public static final String IMPORT_CUSTOMERS_JOB = "importCustomersJob";
  public static final String CLEANUP_JOB = "cleanupJob";

  public static final Map<String, String> JOB_DESCRIPTIONS = Map.of(
    IMPORT_CUSTOMERS_JOB, "Imports customers (demo chunk job)",
    CLEANUP_JOB, "Nightly cleanup (demo tasklet job)");

  private static final Logger log = LoggerFactory.getLogger(BatchJobsConfig.class);

  @Bean
  Job importCustomersJob(JobRepository jobRepository, Step importCustomersStep,
                         LaunchTrackingListener launchTrackingListener, JobNotificationListener notificationListener) {
    return new JobBuilder(IMPORT_CUSTOMERS_JOB, jobRepository)
      .listener(launchTrackingListener)
      .listener(notificationListener)
      .start(importCustomersStep)
      .build();
  }

  @Bean
  Step importCustomersStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           ListItemReader<Integer> customerReader, CustomerProcessor customerProcessor) {
    ItemWriter<String> writer = chunk -> log.info("Wrote {} customers: {}", chunk.size(), chunk.getItems());
    return new StepBuilder("importCustomersStep", jobRepository)
      .<Integer, String>chunk(5)
      .transactionManager(transactionManager)
      .reader(customerReader)
      .processor(customerProcessor)
      .writer(writer)
      .build();
  }

  @Bean
  @StepScope
  ListItemReader<Integer> customerReader(@Value("#{jobParameters['itemCount'] ?: '20'}") String itemCount) {
    List<Integer> items = IntStream.rangeClosed(1, Integer.parseInt(itemCount)).boxed().toList();
    return new ListItemReader<>(items);
  }

  @Bean
  @StepScope
  CustomerProcessor customerProcessor(@Value("#{jobParameters['itemDelayMs'] ?: '200'}") String itemDelayMs,
                                     @Value("#{jobParameters['failAtItem'] ?: '-1'}") String failAtItem) {
    return new CustomerProcessor(Long.parseLong(itemDelayMs), Integer.parseInt(failAtItem));
  }

  /** A class, not a record: step-scoped beans are CGLIB proxies and records are final. */
  static class CustomerProcessor implements ItemProcessor<Integer, String> {

    private final long delayMs;
    private final int failAt;

    CustomerProcessor(long delayMs, int failAt) {
      this.delayMs = delayMs;
      this.failAt = failAt;
    }

    @Override
    public String process(Integer item) throws Exception {
      Thread.sleep(delayMs);
      if (item == failAt)
        throw new IllegalStateException("Simulated failure at customer " + item);
      return "customer-" + item;
    }
  }

  @Bean
  Job cleanupJob(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                 LaunchTrackingListener launchTrackingListener, JobNotificationListener notificationListener) {
    Step step = new StepBuilder("cleanupStep", jobRepository)
      .tasklet((contribution, chunkContext) -> {
        log.info("Cleaning up temporary data");
        Thread.sleep(1000);
        return RepeatStatus.FINISHED;
      }, transactionManager)
      .build();
    return new JobBuilder(CLEANUP_JOB, jobRepository)
      .listener(launchTrackingListener)
      .listener(notificationListener)
      .start(step)
      .build();
  }
}
