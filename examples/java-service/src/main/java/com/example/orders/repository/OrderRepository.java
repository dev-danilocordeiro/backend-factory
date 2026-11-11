package com.example.orders.repository;

import com.example.orders.domain.Order;
import java.util.List;
import java.util.Optional;

/** Persistence port for orders. */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    /** All orders in creation order. */
    List<Order> findAll();
}
