package com.example.poc.console;

import com.example.poc.scheduling.ManagedSchedules;
import com.example.poc.scheduling.ManagedSchedules.ScheduleBusyException;
import com.example.poc.scheduling.ManagedSchedules.ScheduleNotFoundException;
import com.example.poc.scheduling.ScheduleView;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The neutral schedules API of the batch console, for PoCs whose scheduler has no management API of its own
 * (db-scheduler, JobRunr). Answers 404 in PoCs without a {@link ManagedSchedules} (Quartz: Quartz Manager API).
 */
@RestController
@RequestMapping("/batch-console/api/schedules")
public class SchedulesController {

  private final ObjectProvider<ManagedSchedules> managedSchedules;

  public SchedulesController(ObjectProvider<ManagedSchedules> managedSchedules) {
    this.managedSchedules = managedSchedules;
  }

  @GetMapping
  public List<ScheduleView> schedules() {
    return call(() -> schedules0().schedules());
  }

  @PutMapping("/{id}")
  public ScheduleView reschedule(@PathVariable String id, @RequestBody Reschedule request) {
    return call(() -> schedules0().reschedule(id, request.cronExpression(), request.timeZone()));
  }

  public record Reschedule(String cronExpression, String timeZone) {
  }

  @PostMapping("/{id}/pause")
  public ScheduleView pause(@PathVariable String id) {
    return call(() -> schedules0().pause(id));
  }

  @PostMapping("/{id}/resume")
  public ScheduleView resume(@PathVariable String id) {
    return call(() -> schedules0().resume(id));
  }

  private ManagedSchedules schedules0() {
    ManagedSchedules schedules = managedSchedules.getIfAvailable();
    if (schedules == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are managed through the scheduler's own API");
    return schedules;
  }

  private static <T> T call(Supplier<T> action) {
    try {
      return action.get();
    }
    catch (ScheduleNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
    catch (ScheduleBusyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
    catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }
}
