# Review

## Findings
- [non-blocking] examples/java-service/src/main/java/com/example/orders/api/CustomerController.java:443: `CustomerController` returns `OrderController.Response`, so the customer routes depend on a type nested inside the orders controller. Both classes are in the api layer, so the architecture rules allow this, but changing `OrderController` could now break `CustomerController`. Fix: later, move `Response` (and the shared `handle` body-writing code) into its own api-level class, e.g. `api/Response.java`.
- [non-blocking] examples/java-service/src/main/java/com/example/orders/api/CustomerController.java:443: `route` accepts an `InputStream body` but never reads it. It is there only to keep the same signature as `OrderController.route`. Fix: drop the parameter, or leave a comment saying why it is kept.
- [non-blocking] examples/java-service/src/main/java/com/example/orders/service/OrderService.java:485: `totalCentsFor` adds with plain `long`, so a sum past the `long` range wraps around silently. The spec says overflow is not handled, so this is within scope. Fix: if it ever matters, use `Math.addExact` in a `reduce` and map the overflow to an error.

All 9 acceptance criteria are implemented and tested:
- **AC1–AC7:** each has a `CustomerControllerTest` test and an `AppTest` test over real HTTP. The tests compare the exact JSON string, so a wrong total, the wrong field order or a quoted number would fail. AC1 and AC6 also check the `Content-Type` header.
- **AC8:** the unchanged `ArchitectureTest` covers it. The api package does not import the repository; only `OrderService` reads it. The issue's hint to read the repository from the controller was correctly not followed.
- **AC9:** the existing `OrderControllerTest` and `OrderServiceTest` still pass. `AppTest.ordersRoutesStillServed` and `AppTest.getOrderByIdStillServed` check the `/orders` wiring after the `App` refactor.

Other points checked:
- **Concurrency:** `InMemoryOrderRepository.findAll()` is `synchronized` and returns a copy, so summing it is safe while other requests run.
- **Shared service:** `App.createServer` gives one `OrderService` to both controllers, so totals see the orders that `POST /orders` saves.
- **Customer ID in the response:** the ID comes from the path and is echoed back. Jackson escapes it and the response is `application/json`, so it cannot inject anything.
- **Scope:** outside the `.factory/4` pipeline files, only `examples/java-service` changed.

## Not verified
- **Encoded slashes in the ID:** `getRequestURI().getPath()` decodes the path. An ID containing `%2F` (e.g. `/customers/a%2Fb/total`) becomes extra path segments and returns 404 instead of a total. I reasoned this from the JDK API and did not run it. Today's `/orders/{id}` behaves the same way, and no spec criterion covers it.
- **How slow the full scan gets:** `totalCentsFor` reads every order on each request. I did not measure this with a large number of orders.
- **Gate results:** I relied on `gates.json` and the test counts reported in `.factory/4/test.md` (25 tests). I did not run Maven myself.
- **Working tree check blocked:** my command to read files and run `git status` from inside `examples/java-service` was denied, because it needed approval and this session cannot grant it. I read the needed files with the file reader instead, so I did not check for uncommitted files outside the diff.

## Risks
- **Clients that read the old 404 for `/customers`:** `GET /customers` and other `/customers...` paths now return the JSON `{"error":"no route"}` 404 instead of the HTTP server's default 404 page. AC7 asks for this. It only matters if a client relied on the old response; I judge that unlikely.
- **Server startup refactor:** `main` now calls `createServer(port).start()`. The `PORT` variable and the "listening on" log line are unchanged. I judge the chance of a regression very low.
- **Load:** each total request reads and copies the whole in-memory repository while holding its lock. This is fine at example scale. A large dataset or a real persistence backend would need a query that filters by customer. I judge this unlikely to matter for this service.

VERDICT: APPROVE
