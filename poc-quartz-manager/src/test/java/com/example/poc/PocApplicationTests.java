package com.example.poc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.fabioformosa.quartzmanager.api.jobs.entities.LogRecord;
import it.fabioformosa.quartzmanager.api.websockets.WebSocketLogsNotifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PocApplicationTests {

  private static final String BASIC_AUTH = "Basic " + Base64.getEncoder().encodeToString("uc4:uc4-secret".getBytes(StandardCharsets.UTF_8));

  @Autowired
  MockMvc mvc;

  @Autowired
  JobRepository jobRepository;

  @MockitoBean
  JavaMailSender mailSender;

  @MockitoSpyBean
  WebSocketLogsNotifier logsNotifier;

  @AfterEach
  void resetOwner() throws Exception {
    mvc.perform(api(put("/api/jobs/importCustomersJob/owner")).content("{\"owner\":\"INTERNAL\"}"));
  }

  @Test
  void apiRequiresTechnicalUser() throws Exception {
    mvc.perform(post("/api/jobs/importCustomersJob/start")).andExpect(status().isUnauthorized());
  }

  @Test
  void quartzManagerOnlyOffersConcreteJobClasses() throws Exception {
    mvc.perform(get("/quartz-manager/job-classes"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void quartzManagerUiRoutesSurviveBrowserRefresh() throws Exception {
    mvc.perform(get("/quartz-manager-ui/manager")).andExpect(forwardedUrl("/quartz-manager-ui/index.html"));
    mvc.perform(get("/quartz-manager-ui/")).andExpect(forwardedUrl("/quartz-manager-ui/index.html"));
    mvc.perform(get("/quartz-manager-ui")).andExpect(redirectedUrl("/quartz-manager-ui/"));
    mvc.perform(get("/quartz-manager-ui/missing.js")).andExpect(status().isNotFound());
  }

  @Test
  void startViaApiRunsThroughQuartzAndCompletes() throws Exception {
    JobExecution execution = startAndAwait("{\"itemCount\":\"7\",\"itemDelayMs\":\"0\"}");

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(execution.getJobParameters().getString("startedBy")).isEqualTo("api");
    assertThat(execution.getStepExecutions()).singleElement()
      .satisfies(step -> assertThat(step.getWriteCount()).isEqualTo(7));
    // Other tests' runs may still notify asynchronously, so only look at mails for this execution
    verify(mailSender, never()).send(argThat((SimpleMailMessage mail) -> mail.getSubject().contains("#" + execution.getId() + " ")));
  }

  @Test
  void runEventsAreStreamedToTheLogPanelOfTheJobsTrigger() throws Exception {
    JobExecution execution = startAndAwait("{\"itemCount\":\"7\",\"itemDelayMs\":\"0\"}");

    ArgumentCaptor<LogRecord> records = ArgumentCaptor.forClass(LogRecord.class);
    verify(logsNotifier, timeout(5000).atLeast(5)).send(eq("importCustomersJob-cron"), records.capture());
    // containsSubsequence tolerates records of other runs in between
    assertThat(records.getAllValues()).extracting(LogRecord::getMessage)
      .containsSubsequence(
        "importCustomersJob #" + execution.getId() + " started by api | parameters: itemCount=7, itemDelayMs=0",
        "importCustomersStep started",
        "importCustomersStep: chunk 1 written, 5 items (5 total)",
        "importCustomersStep: chunk 2 written, 2 items (7 total)");
    assertThat(records.getAllValues()).extracting(LogRecord::getMessage)
      .anySatisfy(message -> assertThat(message).startsWith("importCustomersStep COMPLETED"))
      .anySatisfy(message -> assertThat(message).startsWith("importCustomersJob #" + execution.getId() + " COMPLETED"));
  }

  @Test
  void failedRunSendsNotification() throws Exception {
    JobExecution execution = startAndAwait("{\"failAtItem\":\"2\",\"itemDelayMs\":\"0\"}");

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
    ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, timeout(5000)).send(mail.capture());
    assertThat(mail.getValue().getSubject()).isEqualTo("[batch] importCustomersJob #" + execution.getId() + " FAILED");
    assertThat(mail.getValue().getText()).contains("Simulated failure at customer 2");
  }

  @Test
  void runningExecutionCanBeStoppedGracefully() throws Exception {
    long id = start("{\"itemCount\":\"100\",\"itemDelayMs\":\"100\"}");
    Thread.sleep(700);

    mvc.perform(api(post("/api/executions/" + id + "/stop"))).andExpect(status().isAccepted());

    JobExecution execution = await(id);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.STOPPED);
    assertThat(execution.getStepExecutions().iterator().next().getReadCount()).isLessThan(100);
    mvc.perform(api(post("/api/executions/" + id + "/stop"))).andExpect(status().isConflict());
  }

  @Test
  void handingJobToUc4PausesInternalTriggers() throws Exception {
    mvc.perform(api(put("/api/jobs/importCustomersJob/owner")).content("{\"owner\":\"UC4\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.owner").value("UC4"))
      .andExpect(jsonPath("$.triggers[0].state").value("PAUSED"));

    // External starts keep working while UC4 owns the job
    assertThat(startAndAwait("{\"itemCount\":\"1\",\"itemDelayMs\":\"0\"}").getStatus()).isEqualTo(BatchStatus.COMPLETED);
  }

  @Test
  void unknownJobAndExecutionAreNotFound() throws Exception {
    mvc.perform(api(post("/api/jobs/nope/start"))).andExpect(status().isNotFound());
    mvc.perform(api(get("/api/executions/987654"))).andExpect(status().isNotFound());
  }

  private JobExecution startAndAwait(String parameters) throws Exception {
    return await(start(parameters));
  }

  private long start(String parameters) throws Exception {
    String body = mvc.perform(api(post("/api/jobs/importCustomersJob/start")).content(parameters))
      .andExpect(status().isAccepted())
      .andReturn().getResponse().getContentAsString();
    Number id = JsonPath.read(body, "$.jobExecutionId");
    assertThat(id).as("jobExecutionId in %s", body).isNotNull();
    return id.longValue();
  }

  private JobExecution await(long id) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      JobExecution execution = jobRepository.getJobExecution(id);
      if (!execution.isRunning())
        return execution;
      Thread.sleep(100);
    }
    throw new AssertionError("Execution " + id + " did not finish");
  }

  private static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, BASIC_AUTH).contentType(MediaType.APPLICATION_JSON);
  }
}
