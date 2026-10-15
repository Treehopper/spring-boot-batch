package com.example.poc.notification;

import com.example.poc.config.PocProperties;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends an email when a run ends with one of the configured statuses. Sits on the Spring Batch layer, so it covers
 * every run regardless of who started it (schedule, manual start, UC4 API).
 */
@Component
public class JobNotificationListener implements JobExecutionListener {

  private static final Logger log = LoggerFactory.getLogger(JobNotificationListener.class);

  private final JavaMailSender mailSender;
  private final Set<BatchStatus> notifyOn;
  private final String from;
  private final String[] to;

  public JobNotificationListener(JavaMailSender mailSender, PocProperties properties) {
    this.mailSender = mailSender;
    this.notifyOn = properties.notification().notifyOn();
    this.from = properties.notification().from();
    this.to = properties.notification().to().toArray(String[]::new);
  }

  @Override
  public void afterJob(JobExecution execution) {
    if (!notifyOn.contains(execution.getStatus()))
      return;
    String jobName = execution.getJobInstance().getJobName();
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(to);
    message.setSubject("[batch] %s #%d %s".formatted(jobName, execution.getId(), execution.getStatus()));
    message.setText(body(execution));
    try {
      mailSender.send(message);
      log.info("Sent {} notification for {} #{} to {}", execution.getStatus(), jobName, execution.getId(), String.join(",", to));
    }
    catch (MailException e) {
      // Never let a notification problem change the outcome of the job
      log.error("Could not send notification for {} #{}", jobName, execution.getId(), e);
    }
  }

  private static String body(JobExecution execution) {
    Duration duration = execution.getStartTime() != null && execution.getEndTime() != null
      ? Duration.between(execution.getStartTime(), execution.getEndTime())
      : Duration.ZERO;
    StringBuilder sb = new StringBuilder()
      .append("Job:        ").append(execution.getJobInstance().getJobName()).append('\n')
      .append("Execution:  ").append(execution.getId()).append('\n')
      .append("Status:     ").append(execution.getStatus()).append(" / ").append(execution.getExitStatus().getExitCode()).append('\n')
      .append("Started:    ").append(execution.getStartTime()).append('\n')
      .append("Ended:      ").append(execution.getEndTime()).append('\n')
      .append("Duration:   ").append(duration.toMillis()).append(" ms\n")
      .append("Parameters: ");
    execution.getJobParameters().parameters().stream()
      .map(p -> p.name() + "=" + p.value())
      .sorted()
      .forEach(p -> sb.append(p).append(' '));
    sb.append("\n\n");
    execution.getStepExecutions().forEach(step -> sb
      .append("Step ").append(step.getStepName()).append(": ").append(step.getStatus())
      .append(" read=").append(step.getReadCount())
      .append(" write=").append(step.getWriteCount())
      .append(" skip=").append(step.getSkipCount()).append('\n'));
    execution.getAllFailureExceptions().forEach(e -> sb.append("\nFailure: ").append(e).append("\nRoot cause: ").append(rootCause(e)));
    return sb.toString();
  }

  private static Throwable rootCause(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause)
      cause = cause.getCause();
    return cause;
  }
}
