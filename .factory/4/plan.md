# Plan

## Files
- `examples/java-service/src/main/java/com/example/orders/service/OrderService.java`: add `public long totalCentsFor(String customerId)`. It sums `Order::totalCents` over `repository.findAll()`, keeping only orders whose `customerId` equals the argument. It returns `0` when nothing matches.
- `examples/java-service/src/main/java/com/example/orders/api/CustomerTotal.java` (new): the response DTO `public record CustomerTotal(String customerId, long totalCents)`, annotated `@JsonPropertyOrder({"customerId","totalCents"})` so field order is fixed.
- `examples/java-service/src/main/java/com/example/orders/api/CustomerController.java` (new): an `HttpHandler` for the `/customers` context. It talks only to `OrderService`. It has the same `handle()` shape as `OrderController` (JSON body, `Content-Type: application/json`). It also has a public `route(method, path, body)` that returns `OrderController.Response`.
- `examples/java-service/src/main/java/com/example/orders/App.java`: move server wiring into `static HttpServer createServer(int port)`. That method creates the server, registers `/orders` → `OrderController` and `/customers` → `CustomerController` (both sharing one `OrderService`), and sets the executor. `main` calls `createServer(port).start()` and prints the same "listening on" line.
- `examples/java-service/src/test/java/com/example/orders/api/CustomerControllerTest.java` (new): routing and body tests.
- `examples/java-service/src/test/java/com/example/orders/service/OrderServiceTest.java`: add the service-level total tests.
- `examples/java-service/src/test/java/com/example/orders/AppTest.java` (new): end-to-end HTTP test on an ephemeral port. It covers the wiring, the Content-Type header and the trailing slash.

## Steps
1. In `OrderService`, add:
   `public long totalCentsFor(String customerId) { return repository.findAll().stream().filter(o -> Objects.equals(o.customerId(), customerId)).mapToLong(Order::totalCents).sum(); }`
   Import `java.util.Objects`.
2. Create `api/CustomerTotal.java` as the record described above, with `@JsonPropertyOrder` from `com.fasterxml.jackson.annotation`. The api layer may use Jackson; only the domain may not.
3. Create `api/CustomerController.java`:
   - Hold `private static final ObjectMapper JSON` and `private final OrderService service`.
   - Make `handle(HttpExchange)` identical in shape to `OrderController.handle`. It calls `route(...)`, writes the body with `JSON.writeValueAsBytes`, sets `Content-Type: application/json`, then sends the status.
   - `route(String method, String path, InputStream body)`:
     - Strip trailing slashes with `path.replaceAll("/+$", "")`, the same way `OrderController` does, then split on `/`.
     - If `"GET".equals(method)`, `parts.length == 4`, `"customers".equals(parts[1])`, `!parts[2].isBlank()` and `"total".equals(parts[3])`: return `new OrderController.Response(200, new CustomerTotal(parts[2], service.totalCentsFor(parts[2])))`.
     - Otherwise return `new OrderController.Response(404, Map.of("error", "no route"))`.
4. Refactor `App`. Add `static HttpServer createServer(int port) throws IOException` with the wiring above, including the new `server.createContext("/customers", new CustomerController(service))`. `main` reads `PORT`, calls `createServer(port)`, then `start()`, then prints.
5. Add the tests listed below.
6. Run `mvn -f examples/java-service verify` (or the project's usual test command). Confirm that `ArchitectureTest`, `OrderControllerTest`, `OrderServiceTest` and the new tests all pass.

## Tests
In every controller test, check the body by serializing it with `new ObjectMapper().writeValueAsString(resp.body())` and comparing the result to the exact expected JSON string.

1. **AC1:**
   - `CustomerControllerTest.totalSumsAllOrdersOfCustomer`: creates the two c1 orders through `OrderController.route("POST","/orders",...)` on a shared `OrderService`. It then checks that `GET /customers/c1/total` returns status 200 and body `{"customerId":"c1","totalCents":350}`.
   - `AppTest.customerTotalOverHttp`: calls `App.createServer(0)`, starts it, POSTs both orders with `java.net.http.HttpClient`, then GETs `/customers/c1/total`. It checks status 200, the `Content-Type` header `application/json` and the exact body. It stops the server in `finally`.
2. **AC2:**
   - `CustomerControllerTest.otherCustomersOrdersAreNotCounted` checks for `{"customerId":"c1","totalCents":200}`.
   - `OrderServiceTest.totalCentsForIgnoresOtherCustomers` checks the same filtering at service level.
3. **AC3:**
   - `CustomerControllerTest.customerWithoutOrdersHasZeroTotal` sends the request once with an empty repository and once after creating orders for c1. Both times it expects status 200 and `{"customerId":"c9","totalCents":0}`.
   - `OrderServiceTest.totalCentsForUnknownCustomerIsZero` checks the same case at service level.
4. **AC4:** `CustomerControllerTest.totalIsJsonInteger` checks the exact string `{"customerId":"c1","totalCents":12345}`, which has no quotes or decimal point on the number.
5. **AC5:**
   - `CustomerControllerTest.trailingSlashIsIgnored` checks that the status and serialized body of `/customers/c1/total/` match those of `/customers/c1/total`.
   - `AppTest.customerTotalTrailingSlashOverHttp` checks the same over real HTTP.
6. **AC6:** `CustomerControllerTest.postToTotalIsNoRoute` expects status 404 and body `{"error":"no route"}`.
7. **AC7:** `CustomerControllerTest.otherCustomerPathsAreNoRoute` checks `GET /customers/c1`, `GET /customers` and `GET /customers//total`. Each must return 404 with `{"error":"no route"}`.
8. **AC8:** the existing `ArchitectureTest` (`LAYERS` and `DOMAIN_IS_PLAIN`), left unchanged.
9. **AC9:**
   - The existing `OrderControllerTest` tests: `createThenListAndGet`, `unknownOrderIs404` and `malformedJsonIs400`.
   - The existing `OrderServiceTest` tests: `createsAndFindsOrder` and `rejectsInvalidOrders`.
   - `AppTest.ordersRoutesStillServed` sends `POST /orders` and `GET /orders` over HTTP to check the `/orders` wiring after the `App` refactor.

## Layering
- **Service** (`OrderService.totalCentsFor`): the only class that reads the repository. This satisfies `Repository mayOnlyBeAccessedByLayers("Service")`. The issue suggested having the controller read the repository directly; the plan does not do that.
- **Api** (`CustomerController`, `CustomerTotal`):
  - These classes depend only on `service`, `domain` (indirectly through the service's return types), Jackson and `com.sun.net.httpserver`.
  - Nothing in the api package imports `..repository..`.
  - `Service mayOnlyBeAccessedByLayers("Api")` is satisfied.
  - Nothing outside the api package uses these classes except `App`, and `ArchitectureTest` ignores `App`'s dependencies.
  - Using `OrderController.Response` from `CustomerController` is a dependency inside one layer, which the rules allow.
- **Domain:** unchanged, so `DOMAIN_IS_PLAIN` still holds. The DTO and its Jackson annotation live in api, not domain.
- **App** (root package): wiring only. Its dependencies are excluded by `ignoreDependency(App.class, Object.class)`.
- **Tests:** `AppTest` lives in the test tree, which `ArchitectureTest` excludes through `DoNotIncludeTests`.
- No changes to `ArchitectureTest.java`, `pom.xml` or anything outside `examples/java-service`.

## Risks
- **Shared repository:** `App` must give one `OrderService` instance to both controllers. Otherwise totals would come from a different repository than the one `POST /orders` writes to. `AppTest` checks this.
- **Prefix matching:** `HttpServer` matches context paths by prefix. The `/customers` context therefore also receives paths like `/customersX/...`. The strict `parts[1]` check returns "no route" for these. `/orders` routing is not touched.
- **Behaviour change in `GET /customers`:** before this change, `/customers` had no context and `HttpServer` returned its default 404 page. Now it returns the JSON 404 `{"error":"no route"}`. This is what AC7 asks for.
- **The `App` refactor:** `main` keeps the same behaviour, the same `PORT` environment variable and the same log line. `createServer` is package-private, so it adds no public API.
- **Performance:** `totalCentsFor` scans every order (O(n)). That is fine for the in-memory repository and is noted as out of scope for persistence changes.
- **Overflow:** a sum beyond the `long` range is not handled, as the spec accepts. `Order.totalCents()` multiplies an `int` by a `long`, so each line total is already computed as a `long`.
