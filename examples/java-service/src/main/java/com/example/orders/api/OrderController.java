package com.example.orders.api;

import com.example.orders.domain.InvalidOrderException;
import com.example.orders.domain.Order;
import com.example.orders.service.OrderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** HTTP routes for /orders. Talks to OrderService only. */
public final class OrderController implements HttpHandler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response response = route(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestBody());
            byte[] body = JSON.writeValueAsBytes(response.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** Routing kept free of HttpExchange so it can be unit tested. */
    public Response route(String method, String path, InputStream body) throws IOException {
        String[] parts = path.replaceAll("/+$", "").split("/");
        if (parts.length == 2 && "GET".equals(method)) {
            return new Response(200, service.list());
        }
        if (parts.length == 2 && "POST".equals(method)) {
            return create(body);
        }
        if (parts.length == 3 && "GET".equals(method)) {
            return service.find(parts[2])
                    .<Response>map(o -> new Response(200, o))
                    .orElseGet(() -> error(404, "order not found"));
        }
        return error(404, "no route");
    }

    private Response create(InputStream body) throws IOException {
        Order in;
        try {
            in = JSON.readValue(new String(body.readAllBytes(), StandardCharsets.UTF_8), Order.class);
        } catch (JsonProcessingException e) {
            return error(400, "malformed JSON");
        }
        try {
            return new Response(201, service.create(in));
        } catch (InvalidOrderException e) {
            return error(400, e.getMessage());
        }
    }

    private static Response error(int status, String message) {
        return new Response(status, Map.of("error", message));
    }

    /** Status and JSON body of a response. */
    public record Response(int status, Object body) {
    }
}
