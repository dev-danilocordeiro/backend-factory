package com.example.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.orders.domain.InvalidOrderException;
import com.example.orders.domain.Order;
import com.example.orders.repository.InMemoryOrderRepository;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private final OrderService service = new OrderService(new InMemoryOrderRepository());

    @Test
    void createsAndFindsOrder() {
        Order created = service.create(new Order(null, "c1", "A", 2, 150));
        assertEquals("ord-1", created.id());
        assertEquals(300, created.totalCents());
        assertEquals(created, service.find("ord-1").orElseThrow());
    }

    @Test
    void rejectsInvalidOrders() {
        assertThrows(InvalidOrderException.class, () -> service.create(new Order(null, "", "A", 1, 1)));
        assertThrows(InvalidOrderException.class, () -> service.create(new Order(null, "c1", "A", 0, 1)));
        assertThrows(InvalidOrderException.class, () -> service.create(new Order(null, "c1", "A", 1, -1)));
    }
}
