package com.example.poc.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Workaround for db-scheduler-ui 5.0 on Spring Boot 4: for its client-side routes (opened directly or reloaded), the
 * UI's own SPA fallback serves {@code index.html} as an in-memory resource, which Spring Framework 7's resource
 * handling can't serve ({@code FileNotFoundException: Byte array resource ... cannot be resolved to absolute file
 * path}), so the browser got an error page. This forwards those routes to {@code /db-scheduler/index.html}, which the
 * UI serves from a controller, and its React router takes over.
 * <p>
 * Routes of the UI: {@code /}, {@code /:taskName}, {@code /:taskName/page/:page}, {@code /history/all} (served by the
 * UI itself) and {@code /history/:taskName/:taskInstance}. Paths with a dot (assets, favicon) are left alone.
 */
@Controller
public class DbSchedulerUiRoutesController {

  private static final String INDEX = "forward:/db-scheduler/index.html";

  @GetMapping({
    "/db-scheduler/",
    "/db-scheduler/{taskName:[^.]+}",
    "/db-scheduler/{taskName:[^.]+}/page/{page:\\d+}",
    "/db-scheduler/history/{taskName:[^.]+}/{taskInstance:[^.]+}"
  })
  public String index() {
    return INDEX;
  }
}
