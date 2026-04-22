package com.ecom.notificationservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class NotificationMetrics {

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final Counter       notificationsPushed;

    public NotificationMetrics(MeterRegistry registry) {
        // Gauge — reads live AtomicInteger, goes up and down as clients connect/disconnect
        Gauge.builder("sse_active_connections", activeConnections, AtomicInteger::get)
                .description("Currently active SSE connections on this pod")
                .register(registry);

        // Counter — monotonically increasing, total pushes since pod start
        notificationsPushed = Counter.builder("notifications_pushed_total")
                .description("Total SSE events pushed to clients on this pod")
                .register(registry);
    }

    public void connectionOpened()  { activeConnections.incrementAndGet(); }
    public void connectionClosed()  { activeConnections.decrementAndGet(); }
    public int  activeConnections() { return activeConnections.get(); }
    public void incrementPushed()   { notificationsPushed.increment(); }
}
