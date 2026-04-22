package com.ecom.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * JDK 21 — Project Loom: virtual thread executor for fire-and-forget @Async tasks.
     *
     * Virtual threads are managed by the JVM scheduler (not the OS). Each task gets its
     * own virtual thread — no pool sizing, no blocking. Ideal for I/O-bound side effects
     * like metrics increments and structured log writes that must not block the request thread.
     *
     * Contrast with platform-thread pools (Executors.newFixedThreadPool): those block when
     * the pool is exhausted under load. Virtual threads never queue — they park cheaply.
     */
    @Bean("virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor(); // JDK 21
    }
}
