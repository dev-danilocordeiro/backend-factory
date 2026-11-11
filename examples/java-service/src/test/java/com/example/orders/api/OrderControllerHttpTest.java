package com.example.orders.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.orders.repository.InMemoryOrderRepository;
import com.example.orders.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real HttpHandler so the query string is read from the request URI. */
class OrderControllerHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID_ORDER =
            "{\"customerId\":\"c1\",\"sku\":\"A\",\"quantity\":2,\"unitPriceCents\":100}";

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", new OrderController(new OrderService(new InMemoryOrderRepository())));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + pathAndQuery)).GET().build());
    }

    private void seed(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/orders"))
                    .POST(HttpRequest.BodyPublishers.ofString(VALID_ORDER)).build());
            assertEquals(201, r.statusCode());
        }
    }

    @Test
    void defaultsOverHttp() throws Exception {
        seed(25);
        HttpResponse<String> r = get("/orders");
        assertEquals(200, r.statusCode());
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals(0, body.get("page").asInt());
        assertEquals(20, body.get("size").asInt());
        assertEquals(25, body.get("total").asInt());
        assertEquals(20, body.get("items").size());
        assertEquals("ord-1", body.get("items").get(0).get("id").asText());
    }

    @Test
    void queryParamsAreReadFromRequestUri() throws Exception {
        seed(57);
        HttpResponse<String> r = get("/orders?page=2&size=20&foo=bar");
        assertEquals(200, r.statusCode());
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals(2, body.get("page").asInt());
        assertEquals(20, body.get("size").asInt());
        assertEquals(57, body.get("total").asInt());
        assertEquals(17, body.get("items").size());
        assertEquals("ord-41", body.get("items").get(0).get("id").asText());
        assertEquals("ord-57", body.get("items").get(16).get("id").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=-5", "page=abc", "size=ten", "page=1.5",
        "page=", "size="})
    void invalidParamsAre400OverHttp(String query) throws Exception {
        seed(1);
        HttpResponse<String> r = get("/orders?" + query);
        assertEquals(400, r.statusCode());
        assertTrue(MAPPER.readTree(r.body()).get("error").isTextual());
    }

    @Test
    void getByIdStillReturnsSingleOrder() throws Exception {
        seed(1);
        HttpResponse<String> r = get("/orders/ord-1");
        assertEquals(200, r.statusCode());
        JsonNode body = MAPPER.readTree(r.body());
        assertTrue(body.isObject());
        assertEquals("ord-1", body.get("id").asText());
    }
}
