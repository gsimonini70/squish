package com.lucsartech.pdf.config;

import com.lucsartech.pdf.compression.PdfCompressor;
import com.lucsartech.pdf.email.EmailService;
import com.lucsartech.pdf.http.MonitorServer;
import com.lucsartech.pdf.pipeline.CompressionPipeline;
import com.lucsartech.pdf.pipeline.ProgressTracker;
import com.lucsartech.pdf.pipeline.WatchdogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Bean configuration for PDF Compressor components.
 */
@Configuration
public class BeanConfiguration {

    @Bean
    public ProgressTracker progressTracker() {
        return new ProgressTracker();
    }

    @Bean
    public PdfCompressor pdfCompressor(PdfCompressorProperties properties) {
        return new PdfCompressor(properties.getMode());
    }

    @Bean
    public MonitorServer monitorServer(ProgressTracker tracker, PdfCompressorProperties properties) {
        return new MonitorServer(tracker, properties.isDryRun(), properties.getWatchdog().isEnabled(), properties.getMode());
    }

    @Bean
    public CompressionPipeline compressionPipeline(
            PdfCompressorProperties properties,
            ProgressTracker tracker) {
        return new CompressionPipeline(properties, tracker);
    }

    @Bean
    public WatchdogService watchdogService(
            PdfCompressorProperties properties,
            ProgressTracker tracker) {
        return new WatchdogService(properties, tracker);
    }

    @Bean
    @ConditionalOnProperty(prefix = "compressor.email", name = "enabled", havingValue = "true")
    public EmailService emailService(PdfCompressorProperties properties) {
        var email = properties.getEmail();
        return new EmailService(email);
    }
}
