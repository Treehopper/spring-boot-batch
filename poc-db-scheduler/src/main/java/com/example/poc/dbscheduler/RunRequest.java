package com.example.poc.dbscheduler;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** Task data of a one-off run (run now, UC4 start). */
public record RunRequest(String jobName, String requestId, String startedBy, HashMap<String, String> parameters)
  implements Serializable {

  public RunRequest(String jobName, String requestId, String startedBy, Map<String, String> parameters) {
    this(jobName, requestId, startedBy, new HashMap<>(parameters));
  }
}
