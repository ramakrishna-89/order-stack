package com.ecom.orderservice.model;

public enum OrderStatus {
    CREATED,    // order received and persisted — outbox event written
    PUBLISHED,  // outbox poller confirmed Kafka delivery
    FAILED;     // outbox poller failed to deliver to Kafka

    public boolean isTerminal() {
        return switch (this) {
            case FAILED    -> true;
            case CREATED, PUBLISHED -> false;
        };
    }

    public String displayLabel() {
        return switch (this) {
            case CREATED   -> "Order Received";
            case PUBLISHED -> "Published to Kafka";
            case FAILED    -> "Publish Failed";
        };
    }
}
