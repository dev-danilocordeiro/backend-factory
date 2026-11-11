package com.example.orders.service;

import com.example.orders.domain.InvalidOrderException;
import com.example.orders.domain.InvalidPageException;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderPage;
import com.example.orders.repository.OrderRepository;
import java.util.List;
import java.util.Optional;

/** Order use cases. The only layer allowed to talk to the repository. */
public final class OrderService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

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

    /** Returns one page of orders in creation order. {@code page} is 0-based. */
    public OrderPage list(int page, int size) {
        if (page < 0) {
            throw new InvalidPageException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidPageException("size must be between 1 and " + MAX_SIZE);
        }
        List<Order> all = repository.findAll();
        int total = all.size();
        long from = (long) page * size;
        int start = (int) Math.min(from, total);
        int end = (int) Math.min((long) start + size, total);
        return new OrderPage(all.subList(start, end), page, size, total);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
