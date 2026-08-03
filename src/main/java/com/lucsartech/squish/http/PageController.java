package com.lucsartech.squish.http;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the two HTML UI pages (dashboard and configuration) as Thymeleaf
 * templates. Both pages are static shells that populate themselves client-side
 * via {@code fetch} against the JSON APIs, so no server-side model data is
 * required here.
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/config")
    public String config() {
        return "config";
    }
}
