package com.example.orders.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orders.repository.InMemoryOrderRepository;
import com.example.orders.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CustomerControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NO_ROUTE = "{\"error\":\"no route\"}";
    private final OrderService service = new OrderService(new InMemoryOrderRepository());
    private final OrderController orders = new OrderController(service);
    private final CustomerController controller = new CustomerController(service);

    private static InputStream json(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private void order(String customerId, String sku, int quantity, long unitPriceCents) throws IOException {
        String body = "{\"customerId\":\"" + customerId + "\",\"sku\":\"" + sku + "\",\"quantity\":" + quantity
                + ",\"unitPriceCents\":" + unitPriceCents + "}";
        assertEquals(201, orders.route("POST", "/orders", json(body)).status());
    }

    private OrderController.Response get(String path) {
        return controller.route("GET", path, json(""));
    }

    private static void assertResponse(int status, String body, OrderController.Response resp) throws IOException {
        assertEquals(status, resp.status());
        assertEquals(body, JSON.writeValueAsString(resp.body()));
    }

    @Test
    void totalSumsAllOrdersOfCustomer() throws IOException {
        order("c1", "A", 2, 100);
        order("c1", "B", 3, 50);
        assertResponse(200, "{\"customerId\":\"c1\",\"totalCents\":350}", get("/customers/c1/total"));
    }

    @Test
    void otherCustomersOrdersAreNotCounted() throws IOException {
        order("c1", "A", 2, 100);
        order("c2", "A", 1, 999);
        assertResponse(200, "{\"customerId\":\"c1\",\"totalCents\":200}", get("/customers/c1/total"));
    }

    @Test
    void customerWithoutOrdersHasZeroTotal() throws IOException {
        String expected = "{\"customerId\":\"c9\",\"totalCents\":0}";
        assertResponse(200, expected, get("/customers/c9/total"));
        order("c1", "A", 2, 100);
        assertResponse(200, expected, get("/customers/c9/total"));
    }

    @Test
    void totalIsJsonInteger() throws IOException {
        order("c1", "A", 1, 12345);
        assertResponse(200, "{\"customerId\":\"c1\",\"totalCents\":12345}", get("/customers/c1/total"));
    }

    @Test
    void trailingSlashIsIgnored() throws IOException {
        order("c1", "A", 2, 100);
        OrderController.Response plain = get("/customers/c1/total");
        OrderController.Response slash = get("/customers/c1/total/");
        assertEquals(plain.status(), slash.status());
        assertEquals(JSON.writeValueAsString(plain.body()), JSON.writeValueAsString(slash.body()));
    }

    @Test
    void postToTotalIsNoRoute() throws IOException {
        assertResponse(404, NO_ROUTE, controller.route("POST", "/customers/c1/total", json("")));
    }

    @Test
    void otherCustomerPathsAreNoRoute() throws IOException {
        assertResponse(404, NO_ROUTE, get("/customers/c1"));
        assertResponse(404, NO_ROUTE, get("/customers"));
        assertResponse(404, NO_ROUTE, get("/customers//total"));
    }
}
