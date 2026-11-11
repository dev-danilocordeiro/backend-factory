# Implementation

The new endpoint `GET /customers/{customerId}/total` is in place, and `mvn verify` passes: 19 tests, 0 failures. That includes the unchanged `ArchitectureTest` rules and all existing `/orders` tests.

## Changes
- `examples/java-service/src/main/java/com/example/orders/service/OrderService.java`: added `totalCentsFor(String customerId)`, which adds up `Order::totalCents` for that customer's orders and returns 0 if there are none. The repository is only read here, in the service layer.
- `examples/java-service/src/main/java/com/example/orders/api/CustomerTotal.java` (new): the response record `CustomerTotal(String customerId, long totalCents)`, with the JSON field order fixed.
- `examples/java-service/src/main/java/com/example/orders/api/CustomerController.java` (new): handles `/customers` and calls only `OrderService`. `GET /customers/{id}/total` returns 200 with the total; trailing slashes are ignored. Anything else returns 404 `{"error":"no route"}`, including a blank ID.
- `examples/java-service/src/main/java/com/example/orders/App.java`: moved the server setup into a package-private `createServer(int port)`. It registers `/orders` and `/customers` with one shared `OrderService`. `main` still behaves the same.
- `examples/java-service/src/test/java/com/example/orders/api/CustomerControllerTest.java` (new): 7 tests covering acceptance criteria 1–7. Each compares the exact JSON output.
- `examples/java-service/src/test/java/com/example/orders/service/OrderServiceTest.java`: added `totalCentsForIgnoresOtherCustomers` and `totalCentsForUnknownCustomerIsZero`.
- `examples/java-service/src/test/java/com/example/orders/AppTest.java` (new): real HTTP tests on a random free port. They cover the total endpoint (status, `Content-Type`, body), the trailing slash, and that `/orders` still works after the `App` change.

## Commands run
- First attempt to write the files and run `mvn verify` in one shell command: **blocked** by the permission checker because of how the command was written. Nothing ran. I then made the same changes with the file-editing tools.
- `mvn -q -f examples/java-service/pom.xml verify`: **passed**.
- `mvn -f examples/java-service/pom.xml verify` (re-run to see test counts): **passed**, BUILD SUCCESS.

## Deviations from the plan
- `AppTest` starts and stops the server in `@BeforeEach`/`@AfterEach` instead of a `try/finally` in each test. The effect is the same.
- `CustomerController.route` declares no `throws IOException`, because nothing in it can throw one.
