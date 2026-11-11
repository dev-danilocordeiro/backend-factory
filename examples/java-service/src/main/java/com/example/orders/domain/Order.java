package com.example.orders.domain;

/** A customer order for one SKU. Prices are in cents to avoid floating point. */
public record Order(String id, String customerId, String sku, int quantity, long unitPriceCents) {

    /** Returns a copy of this order with the given id. */
    public Order withId(String newId) {
        return new Order(newId, customerId, sku, quantity, unitPriceCents);
    }

    /** Total price of this order line in cents. */
    public long totalCents() {
        return quantity * unitPriceCents;
    }
}
