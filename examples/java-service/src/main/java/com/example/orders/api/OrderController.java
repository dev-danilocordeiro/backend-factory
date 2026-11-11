package com.example.orders.api;

import com.example.orders.domain.InvalidOrderException;
import com.example.orders.domain.InvalidPageException;
import com.example.orders.domain.Order;
import com.example.orders.service.OrderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** HTTP routes for /orders. Talks to OrderService only. */
public final class OrderController implements HttpHandler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Response response = route(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestBody());
            byte[] body = JSON.writeValueAsBytes(response.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** Routing kept free of HttpExchange so it can be unit tested. */
    public Response route(String method, String path, InputStream body) throws IOException {
        return route(method, path, null, body);
    }

    /** Routing with the raw (still URL-encoded) query string, which may be null. */
    public Response route(String method, String path, String rawQuery, InputStream body) throws IOException {
        String[] parts = path.replaceAll("/+$", "").split("/");
        if (parts.length == 2 && "GET".equals(method)) {
            return list(rawQuery);
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

    private Response list(String rawQuery) {
        try {
            Map<String, String> params = parseQuery(rawQuery);
            int page = intParam(params, "page", OrderService.DEFAULT_PAGE);
            int size = intParam(params, "size", OrderService.DEFAULT_SIZE);
            return new Response(200, service.list(page, size));
        } catch (NumberFormatException e) {
            return error(400, "page and size must be integers");
        } catch (IllegalArgumentException e) {
            return error(400, "malformed query string");
        } catch (InvalidPageException e) {
            return error(400, e.getMessage());
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String segment : rawQuery.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            String key = eq < 0 ? segment : segment.substring(0, eq);
            String value = eq < 0 ? "" : segment.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static int intParam(Map<String, String> params, String key, int defaultValue) {
        String value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!INTEGER.matcher(value).matches()) {
            throw new NumberFormatException(key + " is not an integer");
        }
        return Integer.parseInt(value);
    }

    private static Response error(int status, String message) {
        return new Response(status, Map.of("error", message));
    }

    /** Status and JSON body of a response. */
    public record Response(int status, Object body) {
    }
}
