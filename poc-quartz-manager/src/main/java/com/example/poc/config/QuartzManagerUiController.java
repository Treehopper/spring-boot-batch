package com.example.poc.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The Quartz Manager UI is an Angular single-page app served from the webjar. After loading it rewrites the URL to
 * client-side routes such as {@code /quartz-manager-ui/manager}, which don't exist on the server, so a browser
 * refresh ended in a 404. Serve {@code index.html} for those routes and let the Angular router take over.
 * <p>
 * Paths containing a dot ({@code main.js}, {@code index.html}, fonts, ...) are left to the static resource handler.
 */
@Controller
public class QuartzManagerUiController {

  private static final String UI_PATH = "/quartz-manager-ui/";

  /** Without the trailing slash, the relative script URLs in index.html would resolve against the root. */
  @GetMapping("/quartz-manager-ui")
  public String redirectToUi() {
    return "redirect:" + UI_PATH;
  }

  @GetMapping({UI_PATH, UI_PATH + "{route:[^.]*}"})
  public String forwardToIndex() {
    return "forward:" + UI_PATH + "index.html";
  }
}
