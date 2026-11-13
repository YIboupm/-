package com.compras.procurement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Entry point. The application is a modular monolith: each top-level package is a module
 * (catalog, context, intake, request, approval, consolidation, llm, audit) that talks to
 * the others through service interfaces and domain events only.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ProcurementApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcurementApplication.class, args);
    }

    /** A single clock bean so every "today" in the system can be controlled in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
