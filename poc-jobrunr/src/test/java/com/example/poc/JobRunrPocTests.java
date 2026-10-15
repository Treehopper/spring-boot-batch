package com.example.poc;

import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
  "poc.execution-logs.dir=target/test-execution-logs",
  "jobrunr.dashboard.enabled=false"
})
@AutoConfigureMockMvc
class JobRunrPocTests extends PocContractTest {

  @Override
  protected String schedulerName() {
    return "JobRunr";
  }

  @Override
  protected String cronDialect() {
    return "jobrunr";
  }

  @Override
  protected String weekdaysCron() {
    return "0 45 6 * * MON-FRI";
  }

  @Override
  protected String everyFewSecondsCron() {
    return "*/5 * * * * *";
  }

  @Override
  protected List<String> acceptedCrons() {
    return List.of("0 0 9 * * 5L", "0 59 23 L * *", "0 0/15 * * * *", "30 9 * * *");
  }

  /** No "?" and no "#": JobRunr can't express "the second Tuesday of the month". */
  @Override
  protected List<String> rejectedCrons() {
    return List.of("bogus", "0 0 9 ? * MON", "0 0 9 * * 2#2");
  }
}
