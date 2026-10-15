package com.example.poc.quartz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Live events in the Quartz Manager log panel; {@code chunkEvery} = n sends only every n-th chunk event. */
@ConfigurationProperties("poc.live-log")
public record LiveLogProperties(@DefaultValue("1") int chunkEvery) {
}
