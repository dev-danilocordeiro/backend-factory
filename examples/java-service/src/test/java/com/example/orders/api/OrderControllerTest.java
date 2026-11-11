package com.example.orders.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orders.repository.InMemoryOrderRepository;
import com.example.orders.service.OrderService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderControllerTest {

    private final OrderController controller = new OrderController(new OrderService(new InMemoryOrderRepository()));

    private static InputStream json(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void createThenListAndGet() throws IOException {
        var created = controller.route("POST", "/orders",
                json("{\"customerId\":\"c1\",\"sku\":\"A\",\"quantity\":2,\"unitPriceCents\":100}"));
        assertEquals(201, created.status());
        assertEquals(1, ((List<?>) controller.route("GET", "/orders", json("")).body()).size());
        assertEquals(200, controller.route("GET", "/orders/ord-1", json("")).status());
    }

    @Test
    void unknownOrderIs404() throws IOException {
        assertEquals(404, controller.route("GET", "/orders/nope", json("")).status());
    }

    @Test
    void malformedJsonIs400() throws IOException {
        assertEquals(400, controller.route("POST", "/orders", json("{")).status());
    }
}
