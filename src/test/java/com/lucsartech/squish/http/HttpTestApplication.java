package com.lucsartech.squish.http;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Minimal {@code @SpringBootConfiguration} used as the configuration root for the
 * {@code @WebMvcTest} HTTP slices in this package.
 *
 * <p>Spring Boot's configuration finder walks up from the test's package and stops
 * at the first {@code @SpringBootConfiguration} it meets. Because this class lives
 * in {@code com.lucsartech.squish.http} it is found before the real
 * {@link com.lucsartech.squish.SquishApplication} (one package up). That matters:
 * {@code SquishApplication} is a {@code CommandLineRunner} whose constructor pulls
 * in {@code CompressionPipeline}, which opens a HikariCP/Oracle connection in its
 * own constructor. Loading it here would require a database. This stand-in has no
 * such dependencies, so the sliced context stays pure MVC.
 *
 * <p>{@code @ComponentScan} (of this package) lets {@code @WebMvcTest} discover and
 * filter the controllers under test; everything else the controllers need
 * (SquishProperties, ProgressTracker, SecurityConfig) is supplied by each test. The
 * exclude filters mirror {@code @SpringBootApplication} so that the slice's
 * {@code TypeExcludeFilter} applies - without them the scan would greedily pull in
 * the sibling tests' nested {@code @TestConfiguration} classes and clash on bean names.
 */
@SpringBootConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public class HttpTestApplication {
}
