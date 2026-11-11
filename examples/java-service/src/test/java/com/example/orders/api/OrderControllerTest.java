package com.example.orders.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderPage;
import com.example.orders.repository.InMemoryOrderRepository;
import com.example.orders.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID_ORDER =
            "{\"customerId\":\"c1\",\"sku\":\"A\",\"quantity\":2,\"unitPriceCents\":100}";

    private final OrderController controller = new OrderController(new OrderService(new InMemoryOrderRepository()));

    private static InputStream json(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private void seed(int n) throws IOException {
        for (int i = 0; i < n; i++) {
            assertEquals(201, controller.route("POST", "/orders", json(VALID_ORDER)).status());
        }
    }

    private record JsonResponse(int status, JsonNode body) {
    }

    private JsonResponse getJson(String query) throws IOException {
        OrderController.Response r = controller.route("GET", "/orders", query, json(""));
        return new JsonResponse(r.status(), MAPPER.valueToTree(r.body()));
    }

    private static List<String> itemIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private static List<String> ids(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "ord-" + i).toList();
    }

    private static void assertPage(JsonResponse r, int page, int size, int total) {
        assertEquals(200, r.status());
        assertEquals(page, r.body().get("page").asInt());
        assertEquals(size, r.body().get("size").asInt());
        assertEquals(total, r.body().get("total").asInt());
    }

    private static void assertBadRequest(JsonResponse r) {
        assertEquals(400, r.status());
        assertTrue(r.body().get("error").isTextual());
    }

    @Test
    void createThenListAndGet() throws IOException {
        var created = controller.route("POST", "/orders", json(VALID_ORDER));
        assertEquals(201, created.status());
        assertEquals(1, ((OrderPage) controller.route("GET", "/orders", json("")).body()).items().size());
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

    @Test
    void defaultPageReturnsFirst20Of57() throws IOException {
        seed(57);
        JsonResponse r = getJson(null);
        assertPage(r, 0, 20, 57);
        assertEquals(ids(1, 20), itemIds(r.body()));
    }

    @Test
    void secondPageReturns21To40() throws IOException {
        seed(57);
        JsonResponse r = getJson("page=1&size=20");
        assertPage(r, 1, 20, 57);
        assertEquals(ids(21, 40), itemIds(r.body()));
    }

    @Test
    void lastPartialPageReturns17() throws IOException {
        seed(57);
        JsonResponse r = getJson("page=2&size=20");
        assertPage(r, 2, 20, 57);
        assertEquals(ids(41, 57), itemIds(r.body()));
    }

    @Test
    void pagePastEndIsEmpty() throws IOException {
        seed(57);
        JsonResponse r = getJson("page=3&size=20");
        assertPage(r, 3, 20, 57);
        assertEquals(List.of(), itemIds(r.body()));
    }

    @Test
    void emptyStoreReturnsEmptyPage() throws IOException {
        JsonResponse r = getJson(null);
        assertEquals(200, r.status());
        assertEquals(MAPPER.readTree("{\"items\":[],\"page\":0,\"size\":20,\"total\":0}"), r.body());
    }

    @Test
    void size100ReturnsAll57() throws IOException {
        seed(57);
        JsonResponse r = getJson("size=100");
        assertPage(r, 0, 100, 57);
        assertEquals(ids(1, 57), itemIds(r.body()));
    }

    @Test
    void sizeOnlyDefaultsPageTo0() throws IOException {
        seed(5);
        JsonResponse r = getJson("size=2");
        assertPage(r, 0, 2, 5);
        assertEquals(List.of("ord-1", "ord-2"), itemIds(r.body()));
    }

    @Test
    void pageOnlyDefaultsSizeTo20() throws IOException {
        seed(5);
        JsonResponse r = getJson("page=1");
        assertPage(r, 1, 20, 5);
        assertEquals(List.of(), itemIds(r.body()));
    }

    @Test
    void negativePageIs400() throws IOException {
        seed(3);
        assertBadRequest(getJson("page=-1"));
    }

    @Test
    void sizeZeroIs400() throws IOException {
        seed(3);
        assertBadRequest(getJson("size=0"));
    }

    @Test
    void size101Is400() throws IOException {
        seed(3);
        assertBadRequest(getJson("size=101"));
    }

    @Test
    void negativeSizeIs400() throws IOException {
        seed(3);
        assertBadRequest(getJson("size=-5"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=abc", "size=ten", "page=1.5", "page=", "size="})
    void nonNumericParamsAre400(String query) throws IOException {
        seed(3);
        assertBadRequest(getJson(query));
    }

    @Test
    void getByIdAndPostUnchanged() throws IOException {
        var created = controller.route("POST", "/orders", json(VALID_ORDER));
        assertEquals(201, created.status());
        assertEquals("ord-1", ((Order) created.body()).id());
        var found = controller.route("GET", "/orders/ord-1", json(""));
        assertEquals(200, found.status());
        assertEquals("ord-1", ((Order) found.body()).id());
    }

    @Test
    void unknownParamIgnored() throws IOException {
        seed(3);
        JsonResponse r = getJson("page=0&size=20&foo=bar");
        assertPage(r, 0, 20, 3);
        assertEquals(ids(1, 3), itemIds(r.body()));
    }
}
