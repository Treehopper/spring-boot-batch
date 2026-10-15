package com.example.poc.config;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.batch.core.BatchStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration. Note: Quartz Manager registers its own {@code ConversionService}, so {@code @Value} cannot
 * convert to types like {@link Duration}; {@code @ConfigurationProperties} binding is unaffected.
 */
@ConfigurationProperties("poc")
public record PocProperties(Api api, Notification notification) {

  public record Api(String user, String password, @DefaultValue("5s") Duration startTimeout) {
  }

  public record Notification(@DefaultValue("FAILED") Set<BatchStatus> notifyOn, String from, List<String> to) {
  }
}
