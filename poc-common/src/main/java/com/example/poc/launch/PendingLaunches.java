package com.example.poc.launch;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Correlates an external start request (e.g. from UC4) with the Spring Batch execution that the scheduler's job
 * eventually creates. The request id travels as a job parameter, {@link LaunchTrackingListener} completes the
 * future in {@code beforeJob}.
 * <p>
 * In-memory only, which is fine for a PoC. A real implementation would look the request id up in the
 * {@code BATCH_JOB_EXECUTION_PARAMS} table instead.
 */
@Component
public class PendingLaunches {

  private final Map<String, CompletableFuture<Long>> launches = new ConcurrentHashMap<>();

  public CompletableFuture<Long> register(String requestId) {
    return launches.computeIfAbsent(requestId, id -> new CompletableFuture<>());
  }

  public void started(String requestId, long jobExecutionId) {
    register(requestId).complete(jobExecutionId);
  }

  public void failed(String requestId, Throwable cause) {
    register(requestId).completeExceptionally(cause);
  }

  public Optional<CompletableFuture<Long>> find(String requestId) {
    return Optional.ofNullable(launches.get(requestId));
  }
}
