package com.lucsartech.squish.http;

import com.lucsartech.squish.metrics.SquishMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prometheus scrape endpoint. The Content-Type must be exactly the Prometheus
 * text exposition format, or scrapers reject the response.
 */
@RestController
public class MetricsController {

    @GetMapping(value = "/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    public String metrics() {
        return SquishMetrics.getInstance().scrape();
    }
}
