package com.example.orders.api;

import com.example.orders.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** HTTP routes for /customers. Talks to OrderService only. */
public final class CustomerController implements HttpHandler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OrderService service;

    public CustomerController(OrderService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            OrderController.Response response = route(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), exchange.getRequestBody());
            byte[] body = JSON.writeValueAsBytes(response.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** Routing kept free of HttpExchange so it can be unit tested. */
    public OrderController.Response route(String method, String path, InputStream body) {
        String[] parts = path.replaceAll("/+$", "").split("/");
        if ("GET".equals(method) && parts.length == 4 && "customers".equals(parts[1])
                && !parts[2].isBlank() && "total".equals(parts[3])) {
            String customerId = parts[2];
            return new OrderController.Response(200,
                    new CustomerTotal(customerId, service.totalCentsFor(customerId)));
        }
        return new OrderController.Response(404, Map.of("error", "no route"));
    }
}
