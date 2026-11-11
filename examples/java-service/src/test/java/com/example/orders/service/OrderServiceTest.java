package com.example.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.orders.domain.InvalidOrderException;
import com.example.orders.domain.InvalidPageException;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderPage;
import com.example.orders.repository.InMemoryOrderRepository;
import java.util.List;
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

    @Test
    void listPagesInCreationOrder() {
        for (int i = 0; i < 5; i++) {
            service.create(new Order(null, "c1", "A", 1, 1));
        }
        OrderPage page = service.list(1, 2);
        assertEquals(List.of("ord-3", "ord-4"), page.items().stream().map(Order::id).toList());
        assertEquals(1, page.page());
        assertEquals(2, page.size());
        assertEquals(5, page.total());
        assertEquals(List.of("ord-5"), service.list(2, 2).items().stream().map(Order::id).toList());
    }

    @Test
    void listRejectsInvalidPageOrSize() {
        assertThrows(InvalidPageException.class, () -> service.list(-1, 20));
        assertThrows(InvalidPageException.class, () -> service.list(0, 0));
        assertThrows(InvalidPageException.class, () -> service.list(0, 101));
    }

    @Test
    void hugePageDoesNotOverflow() {
        service.create(new Order(null, "c1", "A", 1, 1));
        OrderPage page = service.list(Integer.MAX_VALUE, 100);
        assertTrue(page.items().isEmpty());
        assertEquals(1, page.total());
    }
}
