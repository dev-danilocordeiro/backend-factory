package com.example.orders.service;

import com.example.orders.domain.InvalidOrderException;
import com.example.orders.domain.Order;
import com.example.orders.repository.OrderRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Order use cases. The only layer allowed to talk to the repository. */
public final class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    /** Validates and stores a new order. */
    public Order create(Order order) {
        if (isBlank(order.customerId()) || isBlank(order.sku())) {
            throw new InvalidOrderException("customerId and sku are required");
        }
        if (order.quantity() <= 0) {
            throw new InvalidOrderException("quantity must be positive");
        }
        if (order.unitPriceCents() < 0) {
            throw new InvalidOrderException("unitPriceCents must not be negative");
        }
        return repository.save(order);
    }

    public Optional<Order> find(String id) {
        return repository.findById(id);
    }

    public List<Order> list() {
        return repository.findAll();
    }

    /** Sum of all order totals for one customer, in cents. Zero when the customer has no orders. */
    public long totalCentsFor(String customerId) {
        return repository.findAll().stream()
                .filter(o -> Objects.equals(o.customerId(), customerId))
                .mapToLong(Order::totalCents)
                .sum();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
