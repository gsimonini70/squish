package com.lucsartech.squish.http;

import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.http.dto.ConfigResponse;
import com.lucsartech.squish.http.dto.ConfigUpdateRequest;
import com.lucsartech.squish.http.dto.ConfigUpdateResponse;
import com.lucsartech.squish.http.dto.ErrorResponse;
import com.lucsartech.squish.http.dto.ProfileDetailInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * Configuration API. Field names, HTTP status codes and error message shapes are
 * a public contract: the config page JS and external clients depend on them.
 */
@RestController
public class ConfigApiController {

    private final SquishProperties properties;

    public ConfigApiController(SquishProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/config")
    public ConfigResponse getConfig() {
        var profilesMap = new HashMap<String, ProfileDetailInfo>();
        for (var entry : properties.getProfiles().entrySet()) {
            var p = entry.getValue();
            profilesMap.put(entry.getKey(), new ProfileDetailInfo(
                    entry.getKey(),
                    p.getDescription(),
                    p.getMode() != null ? p.getMode().name() : null,
                    p.getCustomScaleFactor(),
                    p.getCustomJpegQuality(),
                    p.isWatermarkEnabled(),
                    p.getWatermarkText(),
                    p.getWatermarkPosition() != null ? p.getWatermarkPosition().name() : null,
                    p.getWatermarkOpacity(),
                    p.getWatermarkFontSize(),
                    p.getWatermarkColor(),
                    p.isPdfaEnabled(),
                    p.getPdfaConformance() != null ? p.getPdfaConformance().name() : null
            ));
        }

        return new ConfigResponse(
                properties.getActiveProfile(),
                properties.isDryRun(),
                profilesMap,
                properties.getMode().name(),
                properties.getWatchdog().isEnabled()
        );
    }

    @PostMapping("/api/config")
    public ResponseEntity<?> updateConfig(@RequestBody ConfigUpdateRequest request) {
        if (request.activeProfile() != null) {
            if (properties.hasProfile(request.activeProfile())) {
                properties.setActiveProfile(request.activeProfile());
                return ResponseEntity.ok(
                        new ConfigUpdateResponse(true, "Profile updated to: " + request.activeProfile()));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, "Profile not found: " + request.activeProfile()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "Invalid request"));
    }

    /**
     * A malformed JSON body arrives as HttpMessageNotReadableException before the
     * handler runs; the legacy server returned "Invalid JSON: <msg>" with status 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "Invalid JSON: " + e.getMessage()));
    }
}
