package com.example.poc.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Lets only log events through that belong to a Spring Batch execution, i.e. carry the {@code jobExecutionId} MDC
 * value set by {@code LaunchTrackingListener}. Used in {@code logback-spring.xml} for the per-execution log files.
 */
public class ExecutionLogFilter extends Filter<ILoggingEvent> {

  public static final String MDC_KEY = "jobExecutionId";

  @Override
  public FilterReply decide(ILoggingEvent event) {
    return event.getMDCPropertyMap().containsKey(MDC_KEY) ? FilterReply.NEUTRAL : FilterReply.DENY;
  }
}
