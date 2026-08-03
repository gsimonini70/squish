package com.lucsartech.squish.http;

import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.http.dto.ConfigUpdateResponse;
import com.lucsartech.squish.http.dto.ErrorResponse;
import com.lucsartech.squish.http.dto.ScopeResponse;
import com.lucsartech.squish.http.dto.ScopeUpdateRequest;
import com.lucsartech.squish.pipeline.ScopeState;
import com.lucsartech.squish.pipeline.WatchdogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Execution-scope API for the watchdog. Lets a trusted dashboard operator steer the running watchdog
 * onto a specific id range (and optionally an allow-listed document type) so that processing can be
 * advanced on a targeted sub-scope before switching to the continuous, full-scope mode — without
 * editing {@code squish.env} or restarting the service.
 *
 * <p>The override is <b>case (a) only</b>: it can only advance not-yet-done records. The tracking-table
 * anti-join still runs every cycle, so an override that overlaps already-processed records simply skips
 * them — it can never re-process or corrupt prior work. The override is in-memory and reverts on restart.
 *
 * <p><b>Safety.</b> {@code idFrom}/{@code idTo} are bound as SQL parameters. A document type is accepted
 * only if it is on {@code squish.query.allowed-doc-types}; anything else is rejected with 400, so no free
 * text can reach the interpolated WHERE fragment. {@code POST} requires HTTP Basic auth (see SecurityConfig).
 *
 * <p>Field names and status codes are a public contract shared with the config-page JS.
 */
@RestController
public class ScopeController {

    private static final Logger log = LoggerFactory.getLogger(ScopeController.class);

    private final SquishProperties properties;
    private final ScopeState scopeState;

    public ScopeController(SquishProperties properties, ScopeState scopeState) {
        this.properties = properties;
        this.scopeState = scopeState;
    }

    @GetMapping("/api/scope")
    public ScopeResponse getScope() {
        var snap = scopeState.snapshot();
        var q = properties.getQuery();
        // Same pure resolver the watchdog uses each cycle, so the operator sees exactly the filter that
        // will run. id-from is passed as the "cursor" so the inactive case reports the configured lower
        // bound rather than the advanced runtime cursor.
        var eff = WatchdogService.resolveScope(properties, snap, properties.getPipeline().getIdFrom());

        return new ScopeResponse(
                snap.active(),
                snap.idFrom(),
                snap.idTo(),
                snap.docType(),
                snap.autoRevert(),
                snap.appliedAt() != null ? snap.appliedAt().toString() : null,
                snap.appliedBy(),
                eff.filter(),
                q.getMasterTableFilter(),
                q.getDocTypeColumn(),
                q.getAllowedDocTypes(),
                properties.getWatchdog().isEnabled(),
                properties.getWatchdog().getPollIntervalSeconds()
        );
    }

    @PostMapping("/api/scope")
    public ResponseEntity<?> updateScope(@RequestBody ScopeUpdateRequest request, Principal principal) {
        String who = principal != null ? principal.getName() : "anonymous";

        // active == false is an explicit "return to the configured scope".
        boolean activate = request.active() == null || request.active();
        if (!activate) {
            scopeState.clear("operator request (" + who + ")");
            return ResponseEntity.ok(new ConfigUpdateResponse(true,
                    "Scope override cleared - back to the configured scope"));
        }

        long idFrom = request.idFrom() == null ? 0L : request.idFrom();
        long idTo = request.idTo() == null ? 0L : request.idTo();
        boolean autoRevert = request.autoRevert() != null && request.autoRevert();
        String docType = (request.docType() == null || request.docType().isBlank())
                ? null : request.docType().trim();

        if (idFrom < 0) {
            return badRequest("idFrom must be >= 0");
        }
        if (idTo < 0) {
            return badRequest("idTo must be >= 0 (0 = no upper bound)");
        }
        if (idTo > 0 && idTo < idFrom) {
            return badRequest("idTo must be >= idFrom");
        }
        if (docType != null) {
            var allowed = properties.getQuery().getAllowedDocTypes();
            if (allowed == null || !allowed.contains(docType)) {
                log.warn("Rejected scope override from '{}': docType '{}' is not in squish.query.allowed-doc-types",
                        who, docType);
                return badRequest("docType not allowed: " + docType);
            }
        }

        scopeState.apply(idFrom, idTo, docType, autoRevert, who);
        return ResponseEntity.ok(new ConfigUpdateResponse(true,
                "Scope override applied - effective from the next watchdog cycle"));
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(400, message));
    }

    /** Mirror ConfigApiController: a malformed JSON body should yield a 400 with a clear message. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "Invalid JSON: " + e.getMessage()));
    }
}
