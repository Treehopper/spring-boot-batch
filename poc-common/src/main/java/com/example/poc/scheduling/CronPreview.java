package com.example.poc.scheduling;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Result of checking a cron expression: valid or not, and if valid, the next fire times. */
public record CronPreview(boolean valid, String error, List<Instant> nextFireTimes) {

  public static final int PREVIEW_COUNT = 5;

  public static CronPreview invalid(String error) {
    return new CronPreview(false, error, List.of());
  }

  public static CronPreview of(List<Instant> nextFireTimes) {
    return nextFireTimes.isEmpty() ? invalid("The expression never fires") : new CronPreview(true, null, nextFireTimes);
  }

  /** Schedulers fall back to UTC or GMT for unknown zone ids without telling, so check them explicitly. */
  public static boolean isKnownZone(String timeZone) {
    return timeZone != null && (ZoneId.getAvailableZoneIds().contains(timeZone) || timeZone.equals("UTC"));
  }
}
