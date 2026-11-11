package com.example.orders.repository;

import com.example.orders.domain.Order;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe in-memory repository. */
public final class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> orders = new LinkedHashMap<>();

    @Override
    public synchronized Order save(Order order) {
        Order stored = order.withId("ord-" + (orders.size() + 1));
        orders.put(stored.id(), stored);
        return stored;
    }

    @Override
    public synchronized Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public synchronized List<Order> findAll() {
        return new ArrayList<>(orders.values());
    }
}
