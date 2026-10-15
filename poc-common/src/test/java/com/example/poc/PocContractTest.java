package com.example.poc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What every scheduler PoC with {@code ManagedSchedules} must do: neutral schedules API, run now, UC4 API, history,
 * logs, stop, notifications. Subclasses add {@code @SpringBootTest}/{@code @AutoConfigureMockMvc} and the
 * scheduler's cron dialect.
 */
public abstract class PocContractTest {

  private static final String BASIC_AUTH = "Basic " + Base64.getEncoder().encodeToString("uc4:uc4-secret".getBytes(StandardCharsets.UTF_8));
  protected static final String SCHEDULES = "/batch-console/api/schedules";

  @Autowired
  protected MockMvc mvc;

  @Autowired
  protected JobRepository jobRepository;

  @MockitoBean
  protected JavaMailSender mailSender;

  /** Name of the scheduler as reported by /server. */
  protected abstract String schedulerName();

  /** Cron dialect as reported by /server. */
  protected abstract String cronDialect();

  /** Weekdays at 06:45 in the scheduler's dialect. */
  protected abstract String weekdaysCron();

  /** A schedule that fires every few seconds, to see scheduled runs in a test. */
  protected abstract String everyFewSecondsCron();

  /** Expressions the dialect accepts and rejects, e.g. "second Tuesday" (Quartz yes, JobRunr no). */
  protected abstract List<String> acceptedCrons();

  protected abstract List<String> rejectedCrons();

  // --- scheduler and schedules ---

  @Test
  void reportsSchedulerAndServerTimeZone() throws Exception {
    mvc.perform(get("/batch-console/api/server"))
      .andExpect(jsonPath("$.timeZone").value(java.time.ZoneId.systemDefault().getId()))
      .andExpect(jsonPath("$.scheduler.name").value(schedulerName()))
      .andExpect(jsonPath("$.scheduler.cronDialect").value(cronDialect()))
      .andExpect(jsonPath("$.scheduler.scheduleApi").value("console"));
    mvc.perform(get("/batch-console/api/jobs"))
      .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains("cleanupJob", "importCustomersJob")));
  }

  @Test
  void schedulesCanBeChangedPausedAndResumed() throws Exception {
    String id = "cleanupJob-cron";
    String original = JsonPath.read(mvc.perform(get(SCHEDULES)).andReturn().getResponse().getContentAsString(),
      "$[?(@.id == '" + id + "')].cronExpression").toString().replaceAll("[\\[\\]\"]", "");
    String originalZone = JsonPath.<List<String>>read(mvc.perform(get(SCHEDULES)).andReturn().getResponse().getContentAsString(),
      "$[?(@.id == '" + id + "')].timeZone").getFirst();
    try {
      mvc.perform(json(put(SCHEDULES + "/" + id), Map.of("cronExpression", weekdaysCron(), "timeZone", "Asia/Tokyo")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cronExpression").value(weekdaysCron()))
        .andExpect(jsonPath("$.timeZone").value("Asia/Tokyo"))
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        // 06:45 in Tokyo is 21:45 UTC the day before
        .andExpect(jsonPath("$.nextRun").value(org.hamcrest.Matchers.endsWith("T21:45:00Z")));

      mvc.perform(post(SCHEDULES + "/" + id + "/pause"))
        .andExpect(jsonPath("$.state").value("PAUSED"))
        .andExpect(jsonPath("$.nextRun").doesNotExist());
      // changing a paused schedule keeps it paused
      mvc.perform(json(put(SCHEDULES + "/" + id), Map.of("cronExpression", weekdaysCron(), "timeZone", "Europe/Vienna")))
        .andExpect(jsonPath("$.state").value("PAUSED"))
        .andExpect(jsonPath("$.timeZone").value("Europe/Vienna"));
      mvc.perform(post(SCHEDULES + "/" + id + "/resume"))
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        .andExpect(jsonPath("$.nextRun").value(org.hamcrest.Matchers.endsWith("T04:45:00Z")));
    }
    finally {
      mvc.perform(json(put(SCHEDULES + "/" + id), Map.of("cronExpression", original, "timeZone", originalZone)));
    }
  }

  @Test
  void invalidSchedulesAreRejected() throws Exception {
    mvc.perform(json(put(SCHEDULES + "/cleanupJob-cron"), Map.of("cronExpression", "bogus", "timeZone", "UTC")))
      .andExpect(status().isBadRequest())
      .andExpect(status().reason(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyOrNullString())));
    mvc.perform(json(put(SCHEDULES + "/cleanupJob-cron"), Map.of("cronExpression", weekdaysCron(), "timeZone", "Mars/Base")))
      .andExpect(status().isBadRequest())
      .andExpect(status().reason("Unknown time zone Mars/Base"));
    mvc.perform(post(SCHEDULES + "/nope/pause")).andExpect(status().isNotFound());
  }

  @Test
  void cronPreviewUsesTheSchedulersDialect() throws Exception {
    for (String cron : acceptedCrons())
      mvc.perform(get("/batch-console/api/cron-preview").param("expression", cron).param("timeZone", "Europe/Vienna"))
        .andExpect(jsonPath("$.valid").value(true))
        .andExpect(jsonPath("$.nextFireTimes.length()").value(5));
    for (String cron : rejectedCrons())
      mvc.perform(get("/batch-console/api/cron-preview").param("expression", cron).param("timeZone", "Europe/Vienna"))
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void schedulesFire() throws Exception {
    String id = "importCustomersJob-cron";
    try {
      mvc.perform(json(put(SCHEDULES + "/" + id), Map.of("cronExpression", everyFewSecondsCron(), "timeZone", "UTC")))
        .andExpect(status().isOk());
      mvc.perform(post(SCHEDULES + "/" + id + "/resume")).andExpect(status().isOk());
      JobExecution scheduled = await(() -> jobRepository.getJobInstances("importCustomersJob", 0, 20).stream()
        .flatMap(instance -> jobRepository.getJobExecutions(instance).stream())
        .filter(execution -> ("schedule:" + id).equals(execution.getJobParameters().getString("startedBy")))
        .findFirst().orElse(null), 30_000);
      assertThat(scheduled).isNotNull();
    }
    finally {
      pauseUntilAccepted(id);
    }
  }

  /** db-scheduler refuses changes while the schedule runs, so retry for a while. */
  protected void pauseUntilAccepted(String id) throws Exception {
    for (int i = 0; i < 100; i++) {
      int status = mvc.perform(post(SCHEDULES + "/" + id + "/pause")).andReturn().getResponse().getStatus();
      if (status == 200)
        return;
      Thread.sleep(200);
    }
  }

  // --- runs, history, logs, stop, notifications ---

  @Test
  void runNowStartsTheJobThroughTheScheduler() throws Exception {
    long before = latestExecutionId();
    mvc.perform(post("/batch-console/api/jobs/cleanupJob/run")).andExpect(status().isAccepted());
    JobExecution execution = await(() -> {
      JobExecution latest = latestExecution("cleanupJob");
      return latest != null && latest.getId() > before && !latest.isRunning() ? latest : null;
    }, 20_000);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(execution.getJobParameters().getString("startedBy")).isEqualTo("manual");
  }

  @Test
  void apiRequiresTechnicalUser() throws Exception {
    mvc.perform(post("/api/jobs/importCustomersJob/start")).andExpect(status().isUnauthorized());
  }

  @Test
  void startViaApiCompletesAndShowsUpInTheConsoleWithItsLog() throws Exception {
    JobExecution execution = startAndAwait("{\"itemCount\":\"7\",\"itemDelayMs\":\"0\"}");

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(execution.getJobParameters().getString("startedBy")).isEqualTo("api");
    mvc.perform(get("/batch-console/api/executions").param("jobName", "importCustomersJob").param("limit", "50"))
      .andExpect(jsonPath("$[?(@.jobExecutionId == " + execution.getId() + ")].steps[0].writeCount").value(7));
    String log = mvc.perform(get("/batch-console/api/executions/" + execution.getId() + "/log"))
      .andReturn().getResponse().getContentAsString();
    assertThat(log).contains("Executing step: [importCustomersStep]", "Wrote 5 customers", "Wrote 2 customers");
    verify(mailSender, never()).send(argThat((SimpleMailMessage mail) -> mail.getSubject().contains("#" + execution.getId() + " ")));
  }

  @Test
  void failedRunSendsNotification() throws Exception {
    JobExecution execution = startAndAwait("{\"failAtItem\":\"2\",\"itemDelayMs\":\"0\"}");

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
    ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, timeout(5000).atLeastOnce()).send(mail.capture());
    assertThat(mail.getAllValues()).anySatisfy(m -> {
      assertThat(m.getSubject()).isEqualTo("[batch] importCustomersJob #" + execution.getId() + " FAILED");
      assertThat(m.getText()).contains("Simulated failure at customer 2");
    });
  }

  @Test
  void runningExecutionCanBeStoppedGracefully() throws Exception {
    long id = start("{\"itemCount\":\"100\",\"itemDelayMs\":\"100\"}");
    Thread.sleep(700);

    mvc.perform(api(post("/api/executions/" + id + "/stop"))).andExpect(status().isAccepted());

    JobExecution execution = await(id);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.STOPPED);
    assertThat(execution.getStepExecutions().iterator().next().getReadCount()).isLessThan(100);
    mvc.perform(post("/batch-console/api/executions/" + id + "/stop")).andExpect(status().isConflict());
  }

  @Test
  void unknownJobAndExecutionAreNotFound() throws Exception {
    mvc.perform(api(post("/api/jobs/nope/start"))).andExpect(status().isNotFound());
    mvc.perform(api(get("/api/executions/987654"))).andExpect(status().isNotFound());
    mvc.perform(post("/batch-console/api/jobs/nope/run")).andExpect(status().isNotFound());
  }

  @Test
  void consolePageIsServed() throws Exception {
    mvc.perform(get("/batch-console/")).andExpect(forwardedUrl("/batch-console/index.html"));
  }

  // --- helpers ---

  protected JobExecution startAndAwait(String parameters) throws Exception {
    return await(start(parameters));
  }

  protected long start(String parameters) throws Exception {
    String body = mvc.perform(api(post("/api/jobs/importCustomersJob/start")).content(parameters))
      .andExpect(status().isAccepted())
      .andReturn().getResponse().getContentAsString();
    Number id = JsonPath.read(body, "$.jobExecutionId");
    assertThat(id).as("jobExecutionId in %s", body).isNotNull();
    return id.longValue();
  }

  protected JobExecution await(long id) throws InterruptedException {
    return await(() -> {
      JobExecution execution = jobRepository.getJobExecution(id);
      return execution.isRunning() ? null : execution;
    }, 20_000);
  }

  private static <T> T await(java.util.function.Supplier<T> condition, long timeoutMs) throws InterruptedException {
    long end = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < end) {
      T result = condition.get();
      if (result != null)
        return result;
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out after " + timeoutMs + " ms");
  }

  private JobExecution latestExecution(String jobName) {
    return jobRepository.getJobInstances(jobName, 0, 1).stream()
      .flatMap(instance -> jobRepository.getJobExecutions(instance).stream())
      .findFirst().orElse(null);
  }

  private long latestExecutionId() {
    return jobRepository.getJobNames().stream()
      .map(this::latestExecution).filter(java.util.Objects::nonNull)
      .mapToLong(JobExecution::getId).max().orElse(0);
  }

  protected static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, BASIC_AUTH).contentType(MediaType.APPLICATION_JSON);
  }

  protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, String> body) {
    StringBuilder json = new StringBuilder("{");
    body.forEach((key, value) -> json.append(json.length() > 1 ? "," : "").append('"').append(key).append("\":\"").append(value).append('"'));
    return request.contentType(MediaType.APPLICATION_JSON).content(json.append('}').toString());
  }
}
