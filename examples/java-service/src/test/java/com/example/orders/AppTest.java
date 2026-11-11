package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppTest {

    private static final String ORDER_A =
            "{\"customerId\":\"c1\",\"sku\":\"A\",\"quantity\":2,\"unitPriceCents\":100}";
    private static final String ORDER_B =
            "{\"customerId\":\"c1\",\"sku\":\"B\",\"quantity\":3,\"unitPriceCents\":50}";

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = App.createServer(0);
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void customerTotalOverHttp() throws IOException, InterruptedException {
        assertEquals(201, post("/orders", ORDER_A).statusCode());
        assertEquals(201, post("/orders", ORDER_B).statusCode());
        HttpResponse<String> resp = get("/customers/c1/total");
        assertEquals(200, resp.statusCode());
        assertEquals("application/json", resp.headers().firstValue("Content-Type").orElse(""));
        assertEquals("{\"customerId\":\"c1\",\"totalCents\":350}", resp.body());
    }

    @Test
    void customerTotalTrailingSlashOverHttp() throws IOException, InterruptedException {
        assertEquals(201, post("/orders", ORDER_A).statusCode());
        HttpResponse<String> plain = get("/customers/c1/total");
        HttpResponse<String> slash = get("/customers/c1/total/");
        assertEquals(plain.statusCode(), slash.statusCode());
        assertEquals(plain.body(), slash.body());
        assertEquals("{\"customerId\":\"c1\",\"totalCents\":200}", slash.body());
    }

    @Test
    void otherCustomersNotCountedOverHttp() throws IOException, InterruptedException {
        assertEquals(201, post("/orders", ORDER_A).statusCode());
        assertEquals(201, post("/orders",
                "{\"customerId\":\"c2\",\"sku\":\"A\",\"quantity\":1,\"unitPriceCents\":999}").statusCode());
        HttpResponse<String> resp = get("/customers/c1/total");
        assertEquals(200, resp.statusCode());
        assertEquals("{\"customerId\":\"c1\",\"totalCents\":200}", resp.body());
    }

    @Test
    void unknownCustomerIsZeroOverHttp() throws IOException, InterruptedException {
        HttpResponse<String> empty = get("/customers/c9/total");
        assertEquals(200, empty.statusCode());
        assertEquals("{\"customerId\":\"c9\",\"totalCents\":0}", empty.body());
        assertEquals(201, post("/orders", ORDER_A).statusCode());
        HttpResponse<String> others = get("/customers/c9/total");
        assertEquals(200, others.statusCode());
        assertEquals("{\"customerId\":\"c9\",\"totalCents\":0}", others.body());
    }

    @Test
    void totalIsJsonIntegerOverHttp() throws IOException, InterruptedException {
        assertEquals(201, post("/orders",
                "{\"customerId\":\"c1\",\"sku\":\"A\",\"quantity\":1,\"unitPriceCents\":12345}").statusCode());
        HttpResponse<String> resp = get("/customers/c1/total");
        assertEquals(200, resp.statusCode());
        assertEquals("{\"customerId\":\"c1\",\"totalCents\":12345}", resp.body());
    }

    @Test
    void postToTotalIsNoRouteOverHttp() throws IOException, InterruptedException {
        HttpResponse<String> resp = post("/customers/c1/total", "{}");
        assertEquals(404, resp.statusCode());
        assertEquals("application/json", resp.headers().firstValue("Content-Type").orElse(""));
        assertEquals("{\"error\":\"no route\"}", resp.body());
    }

    @Test
    void otherCustomerPathsAreNoRouteOverHttp() throws IOException, InterruptedException {
        for (String path : new String[] {"/customers/c1", "/customers"}) {
            HttpResponse<String> resp = get(path);
            assertEquals(404, resp.statusCode(), path);
            assertEquals("{\"error\":\"no route\"}", resp.body(), path);
        }
    }

    @Test
    void getOrderByIdStillServed() throws IOException, InterruptedException {
        HttpResponse<String> created = post("/orders", ORDER_A);
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"id\":\"ord-1\""), created.body());
        HttpResponse<String> one = get("/orders/ord-1");
        assertEquals(200, one.statusCode());
        assertEquals(created.body(), one.body());
        assertEquals(404, get("/orders/nope").statusCode());
    }

    @Test
    void ordersRoutesStillServed() throws IOException, InterruptedException {
        assertEquals(201, post("/orders", ORDER_A).statusCode());
        HttpResponse<String> list = get("/orders");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"customerId\":\"c1\""));
    }
}
