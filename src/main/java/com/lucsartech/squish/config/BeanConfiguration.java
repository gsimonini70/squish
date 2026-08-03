package com.lucsartech.squish.config;

import com.lucsartech.squish.compression.Squish;
import com.lucsartech.squish.email.EmailService;
import com.lucsartech.squish.pipeline.CompressionPipeline;
import com.lucsartech.squish.pipeline.ProgressTracker;
import com.lucsartech.squish.pipeline.ScopeState;
import com.lucsartech.squish.pipeline.WatchdogService;
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
    public ScopeState scopeState() {
        return new ScopeState();
    }

    @Bean
    public Squish pdfCompressor(SquishProperties properties) {
        return new Squish(properties.getActiveCompressionProfile());
    }

    @Bean
    public CompressionPipeline compressionPipeline(
            SquishProperties properties,
            ProgressTracker tracker) {
        return new CompressionPipeline(properties, tracker);
    }

    @Bean
    public WatchdogService watchdogService(
            SquishProperties properties,
            ProgressTracker tracker,
            ScopeState scopeState) {
        return new WatchdogService(properties, tracker, scopeState);
    }

    @Bean
    @ConditionalOnProperty(prefix = "squish.email", name = "enabled", havingValue = "true")
    public EmailService emailService(SquishProperties properties) {
        var email = properties.getEmail();
        return new EmailService(email);
    }
}
