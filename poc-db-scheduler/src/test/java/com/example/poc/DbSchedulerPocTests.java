package com.example.poc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
  "poc.execution-logs.dir=target/test-execution-logs",
  "db-scheduler.polling-interval=1s"
})
@AutoConfigureMockMvc
class DbSchedulerPocTests extends PocContractTest {

  @Override
  protected String schedulerName() {
    return "db-scheduler";
  }

  @Override
  protected String cronDialect() {
    return "quartz";
  }

  @Override
  protected String weekdaysCron() {
    return "0 45 6 ? * MON-FRI";
  }

  @Override
  protected String everyFewSecondsCron() {
    return "0/2 * * * * ?";
  }

  @Override
  protected List<String> acceptedCrons() {
    return List.of("0 0 9 ? * 3#2", "0 0 9 ? * 6L", "0 59 23 L * ?", "0 0/15 * * * ?");
  }

  @Override
  protected List<String> rejectedCrons() {
    return List.of("bogus", "0 0 9 * * MON", "30 9 * * *", "-");
  }

  /** db-scheduler-ui's own SPA fallback fails on Spring Boot 4; see DbSchedulerUiRoutesController. */
  @Test
  void dbSchedulerUiRoutesServeTheApp() throws Exception {
    for (String route : List.of("/db-scheduler/", "/db-scheduler/batch-schedule", "/db-scheduler/batch-schedule/page/2",
      "/db-scheduler/history/batch-schedule/cleanupJob-cron"))
      mvc.perform(get(route)).andExpect(forwardedUrl("/db-scheduler/index.html"));
    mvc.perform(get("/db-scheduler")).andExpect(status().isOk());
  }

  /** db-scheduler can't change a task instance while it executes: the API says so instead of failing. */
  @Test
  void scheduleCannotBeChangedWhileItRuns() throws Exception {
    String id = "importCustomersJob-cron";
    try {
      mvc.perform(json(put(SCHEDULES + "/" + id), Map.of("cronExpression", "* * * * * ?", "timeZone", "UTC")))
        .andExpect(status().isOk());
      mvc.perform(post(SCHEDULES + "/" + id + "/resume")).andExpect(status().isOk());
      long end = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < end) {
        String body = mvc.perform(get(SCHEDULES)).andReturn().getResponse().getContentAsString();
        if (JsonPath.<List<String>>read(body, "$[?(@.id == '" + id + "')].state").contains("RUNNING"))
          break;
        Thread.sleep(50);
      }
      mvc.perform(post(SCHEDULES + "/" + id + "/pause"))
        .andExpect(status().isConflict())
        .andExpect(status().reason(org.hamcrest.Matchers.containsString("in progress")));
    }
    finally {
      pauseUntilAccepted(id);
    }
  }
}
