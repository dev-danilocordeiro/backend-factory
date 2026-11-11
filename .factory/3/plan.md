# Plan

## Files
- `examples/java-service/src/main/java/com/example/orders/domain/OrderPage.java`: new file. A record `OrderPage(List<Order> items, int page, int size, int total)` with a compact constructor that copies `items` using `List.copyOf`. Jackson 2.18 serialises records to `{"items":…,"page":…,"size":…,"total":…}`.
- `examples/java-service/src/main/java/com/example/orders/domain/InvalidPageException.java`: new file. `public final class InvalidPageException extends RuntimeException` with a `(String message)` constructor, written the same way as `InvalidOrderException`.
- `examples/java-service/src/main/java/com/example/orders/service/OrderService.java`: add constants `DEFAULT_PAGE = 0`, `DEFAULT_SIZE = 20` and `MAX_SIZE = 100`. Replace `List<Order> list()` with `OrderPage list(int page, int size)`, which checks the range and returns one page.
- `examples/java-service/src/main/java/com/example/orders/api/OrderController.java`: add `route(String method, String path, String rawQuery, InputStream body)`. The existing 3-argument `route` delegates to it with `null`. `handle` passes `exchange.getRequestURI().getRawQuery()`. The new code parses `page` and `size` from the query and turns parse errors and `InvalidPageException` into a 400 `{"error": …}` response.
- `examples/java-service/src/test/java/com/example/orders/api/OrderControllerTest.java`: update `createThenListAndGet` for the new response shape, and add the pagination tests listed below.
- `examples/java-service/src/test/java/com/example/orders/service/OrderServiceTest.java`: add service-level tests for paging and range checks.

No changes to `OrderRepository` or `InMemoryOrderRepository`. `findAll()` already returns orders in creation order. No changes to `ArchitectureTest.java`, `pom.xml` or checkstyle config.

## Steps
1. Create `domain/InvalidPageException.java`. It extends `RuntimeException` and has only a message constructor.
2. Create `domain/OrderPage.java`:
   - `public record OrderPage(List<Order> items, int page, int size, int total)`.
   - The compact constructor sets `items = List.copyOf(items)`.
   - It imports only `java.util.List`, so the `DOMAIN_IS_PLAIN` rule still holds.
3. In `OrderService`:
   - Add `public static final int DEFAULT_PAGE = 0, DEFAULT_SIZE = 20, MAX_SIZE = 100;`.
   - Replace `list()` with `public OrderPage list(int page, int size)`:
     - if `page < 0`, throw `InvalidPageException("page must be >= 0")`;
     - if `size < 1 || size > MAX_SIZE`, throw `InvalidPageException("size must be between 1 and 100")`;
     - set `all = repository.findAll()` and `total = all.size()`;
     - compute `long from = (long) page * size` (a `long` so a huge `page` cannot overflow) and `int start = (int) Math.min(from, total)`, then `int end = (int) Math.min((long) start + size, total)`;
     - return `new OrderPage(all.subList(start, end), page, size, total)`.
4. In `OrderController`:
   - Add `public Response route(String method, String path, String rawQuery, InputStream body)` and move the current routing body into it. The old `route(method, path, body)` becomes `return route(method, path, null, body);`.
   - In `handle`, call the 4-argument `route` with `exchange.getRequestURI().getRawQuery()`.
   - Replace the `GET /orders` branch with `return list(rawQuery);`.
   - Add `private Response list(String rawQuery)`:
     - call a private static helper `parseQuery(rawQuery)` that returns a `Map<String, String>`: an empty map if the query is null or empty; otherwise split on `&`, skip empty segments, split each on the first `=` (no `=` means the value is `""`), and URL-decode the key and value with `URLDecoder.decode(…, UTF_8)`; if a key repeats, the last value wins;
     - set `page = intParam(params, "page", OrderService.DEFAULT_PAGE)` and `size = intParam(params, "size", OrderService.DEFAULT_SIZE)`;
     - return `new Response(200, service.list(page, size))`;
     - catch `NumberFormatException` and return `error(400, "page and size must be integers")`;
     - catch `IllegalArgumentException` from a malformed `%` escape in `URLDecoder`, which also covers `NumberFormatException`, and return a 400;
     - catch `InvalidPageException e` and return `error(400, e.getMessage())`.
   - `intParam` returns the default when the key is absent. Otherwise it requires the value to match `-?\d+` and throws `NumberFormatException` if it does not; this rejects `""`, `abc`, `ten` and `1.5`. It then returns `Integer.parseInt(value)`, which throws on overflow. Negative values pass parsing so the service rejects them (criteria 9 and 12).
   - Keys other than `page` and `size` are ignored.
5. Update `createThenListAndGet` so it reads `GET /orders` as an `OrderPage`: `((OrderPage) body).items().size() == 1`.
6. Add the tests below, then run `mvn -f examples/java-service verify` (tests, ArchUnit, checkstyle).

## Tests
Shared helpers in `OrderControllerTest`:
- `seed(int n)` POSTs `n` valid orders.
- `getJson(String query)` calls `controller.route("GET", "/orders", query, json(""))`, keeps the status, and turns the body into a `JsonNode` with `new ObjectMapper().valueToTree(body)`. This checks the real JSON shape.
- `itemIds(JsonNode)` returns the list of `items[*].id`.

All controller tests are in `examples/java-service/src/test/java/com/example/orders/api/OrderControllerTest.java`.

| AC | Test |
|----|------|
| 1 | `defaultPageReturnsFirst20Of57`: no query string. Expects 200, page 0, size 20, total 57, and ids `ord-1`…`ord-20` in order. |
| 2 | `secondPageReturns21To40`: query `page=1&size=20`. |
| 3 | `lastPartialPageReturns17`: query `page=2&size=20`. Expects `ord-41`…`ord-57`. |
| 4 | `pagePastEndIsEmpty`: query `page=3&size=20`. Expects empty items and total 57. |
| 5 | `emptyStoreReturnsEmptyPage`: expects the JSON to equal `{"items":[],"page":0,"size":20,"total":0}`, compared by parsing the expected string with `readTree`. |
| 6 | `size100ReturnsAll57`. |
| 7 | `sizeOnlyDefaultsPageTo0`: 5 orders, query `size=2`. Expects `ord-1` and `ord-2`, page 0, total 5. |
| 8 | `pageOnlyDefaultsSizeTo20`: 5 orders, query `page=1`. Expects empty items and size 20. |
| 9 | `negativePageIs400`: expects 400 and `body.get("error").isTextual()`. |
| 10 | `sizeZeroIs400`. |
| 11 | `size101Is400`. |
| 12 | `negativeSizeIs400`. |
| 13 | `nonNumericParamsAre400`: a `@ParameterizedTest` with `@ValueSource` values `page=abc`, `size=ten`, `page=1.5`, `page=` and `size=`. Each must return 400 with a text `error` field. Check that `junit-jupiter-params` is on the test classpath; `junit-jupiter` already includes it. Otherwise use a loop in a plain `@Test`. |
| 14 | `getByIdAndPostUnchanged`: POST returns 201 and the order; `GET /orders/ord-1` returns 200 with an `Order` whose id is `ord-1`. The existing updated `createThenListAndGet` also covers this. |
| 15 | `unknownParamIgnored`: query `page=0&size=20&foo=bar` returns 200. |
| 16 | `examples/java-service/src/test/java/com/example/orders/ArchitectureTest.java`, rules `LAYERS` and `DOMAIN_IS_PLAIN`. The file is unchanged and runs under `mvn verify`. |

Extra tests in `examples/java-service/src/test/java/com/example/orders/service/OrderServiceTest.java`:
- `listPagesInCreationOrder` checks slicing and `total`.
- `listRejectsInvalidPageOrSize` checks that `page=-1`, `size=0` and `size=101` throw `InvalidPageException`.
- `hugePageDoesNotOverflow` calls `list(Integer.MAX_VALUE, 100)` and expects empty items.

## Layering
- **Domain** (`OrderPage`, `InvalidPageException`): plain Java. They depend only on `java.util` and `Order`, with no Jackson or `com.sun.net`, so `DOMAIN_IS_PLAIN` holds. Domain may be used by Api, Service and Repository, and both Api and Service use these types.
- **Service** (`OrderService.list(page, size)`): owns the paging rules (defaults, limits, slicing) and is still the only caller of `OrderRepository`. Only Api uses it, which is allowed.
- **Api** (`OrderController`): HTTP concerns only (query parsing, mapping errors to status codes). It uses Service and Domain and never imports the repository package.
- **Repository**: unchanged.

## Risks
- **Breaking response shape**: the response of `GET /orders` changes from a bare JSON array to an object, which breaks any client that reads it as an array. The spec accepts this (out of scope: keeping the old shape). The one existing test that casts the body to `List` is updated.
- **Removed method**: `OrderService.list()` with no arguments is removed. Its only caller is `OrderController`, but any outside code calling it would fail to compile. If that matters, keep a no-arg overload that delegates to `list(DEFAULT_PAGE, DEFAULT_SIZE)`.
- **Old `route` overload**: the 3-argument `route` still exists, so current callers compile. Through it, `GET /orders` now always returns the default page (first 20 orders) instead of all orders.
- **Edge cases in the query parser**:
  - A `+` in a value decodes to a space and is rejected.
  - A repeated parameter uses its last value (the spec leaves this undefined).
  - A bad percent-escape returns 400 instead of 500, because `IllegalArgumentException` is caught.
- **Cost of `findAll()`**: every request still loads all orders in memory before slicing. That is fine for the in-memory store, and persistence changes are out of scope.
