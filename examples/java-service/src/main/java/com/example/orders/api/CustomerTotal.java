package com.example.orders.api;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Response body for GET /customers/{customerId}/total. */
@JsonPropertyOrder({"customerId", "totalCents"})
public record CustomerTotal(String customerId, long totalCents) {
}
