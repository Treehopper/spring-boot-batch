package com.example.poc.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the batch console (React app built into {@code static/batch-console/} by the frontend build). */
@Controller
public class BatchConsolePageController {

  private static final String CONSOLE_PATH = "/batch-console/";

  /** Without the trailing slash, the relative asset URLs in index.html would resolve against the root. */
  @GetMapping("/batch-console")
  public String redirectToConsole() {
    return "redirect:" + CONSOLE_PATH;
  }

  @GetMapping(CONSOLE_PATH)
  public String index() {
    return "forward:" + CONSOLE_PATH + "index.html";
  }
}
