package com.lucsartech.squish.http;

import com.lucsartech.squish.compression.CompressionResult;
import com.lucsartech.squish.compression.Squish;
import com.lucsartech.squish.config.CompressionProfile;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.http.dto.CompressResponse;
import com.lucsartech.squish.http.dto.ErrorResponse;
import com.lucsartech.squish.metrics.SquishMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

/**
 * REST API: on-demand PDF compression via multipart upload.
 *
 * Mapping POST only makes
 * Spring answer 405 to other methods automatically.
 */
@RestController
@RequestMapping("/api/compress")
public final class CompressController {

    private static final Logger log = LoggerFactory.getLogger(CompressController.class);

    private final SquishProperties properties;

    public CompressController(SquishProperties properties) {
        this.properties = properties;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> compress(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "profile", required = false) String profileName,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "outputPassword", required = false) String outputPassword,
            @RequestParam(value = "format", defaultValue = "binary") String format) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(400, "No file provided. Use 'file' field in multipart/form-data."));
        }

        try {
            byte[] originalBytes = file.getBytes();
            CompressionProfile profile = getProfileForRequest(profileName);

            Squish compressor = new Squish(profile, password, outputPassword);

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "uploaded.pdf";
            long startTime = System.currentTimeMillis();

            CompressionResult result = compressor.compress(0L, 0L, filename, originalBytes);
            long duration = System.currentTimeMillis() - startTime;

            // A no-gain skip means the upload is a valid PDF we simply cannot improve;
            // the caller still gets a PDF back, unchanged.
            byte[] output;
            if (result instanceof CompressionResult.Success success) {
                output = success.compressedData();
            } else if (result instanceof CompressionResult.Skipped skipped) {
                if (!skipped.noGain()) {
                    SquishMetrics.getInstance().recordOnDemand(filename, 0, duration, false);
                    return ResponseEntity.status(400)
                            .body(new ErrorResponse(400, "File skipped: " + skipped.reason()));
                }
                output = originalBytes;
            } else {
                var failure = (CompressionResult.Failure) result;
                SquishMetrics.getInstance().recordOnDemand(filename, 0, duration, false);
                return ResponseEntity.status(500)
                        .body(new ErrorResponse(500, "Compression failed: " + failure.errorMessage()));
            }

            double savings = (1.0 - (double) output.length / originalBytes.length) * 100;
            // Recorded once here for both the success and no-gain paths, before either
            // the json or binary response is built, so it is never double-counted.
            SquishMetrics.getInstance().recordOnDemand(filename, savings, duration, true);

            log.info("REST API: Compressed {} ({} -> {} bytes, {}% saved) in {}ms",
                    filename, originalBytes.length, output.length,
                    String.format("%.1f", savings), duration);

            if ("json".equalsIgnoreCase(format)) {
                var response = new CompressResponse(
                        true,
                        filename,
                        originalBytes.length,
                        output.length,
                        savings,
                        duration,
                        profile.getName(),
                        Base64.getEncoder().encodeToString(output)
                );
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(response);
            }

            String outputFilename = filename.replace(".pdf", "_compressed.pdf");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + outputFilename + "\"")
                    .header("X-Original-Size", String.valueOf(originalBytes.length))
                    .header("X-Compressed-Size", String.valueOf(output.length))
                    .header("X-Savings-Percent", String.format("%.2f", savings))
                    .header("X-Duration-Ms", String.valueOf(duration))
                    .body(output);

        } catch (Exception e) {
            log.error("REST API compress error", e);
            return ResponseEntity.status(500).body(new ErrorResponse(500, "Error: " + e.getMessage()));
        }
    }

    private CompressionProfile getProfileForRequest(String profileName) {
        if (profileName != null && properties.hasProfile(profileName)) {
            return properties.getProfiles().get(profileName);
        }
        return properties.getActiveCompressionProfile();
    }
}
