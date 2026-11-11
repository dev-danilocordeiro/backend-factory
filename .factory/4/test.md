# Tests

All 9 acceptance criteria have tests, and all 25 tests pass. I found no problems in the implementation.

The implementation already had tests for every criterion, but most of them called the controller's routing method directly. I added 6 tests to `AppTest.java` that start the real server and send real HTTP requests. They cover criteria 2, 3, 4, 6, 7 and 9 end to end, including the `Content-Type` header on the 404 response and `GET /orders/{id}`. I changed only test files.

## Coverage of acceptance criteria
1. `AppTest.customerTotalOverHttp` (real HTTP: status, `Content-Type`, body 350) and `CustomerControllerTest.totalSumsAllOrdersOfCustomer`.
2. `AppTest.otherCustomersNotCountedOverHttp` (added), `CustomerControllerTest.otherCustomersOrdersAreNotCounted` and `OrderServiceTest.totalCentsForIgnoresOtherCustomers`.
3. `AppTest.unknownCustomerIsZeroOverHttp` (added; checks both with no orders at all and with only other customers' orders), `CustomerControllerTest.customerWithoutOrdersHasZeroTotal` and `OrderServiceTest.totalCentsForUnknownCustomerIsZero`.
4. `AppTest.totalIsJsonIntegerOverHttp` (added; compares the exact body text, so a string or decimal value would fail) and `CustomerControllerTest.totalIsJsonInteger`.
5. `AppTest.customerTotalTrailingSlashOverHttp` and `CustomerControllerTest.trailingSlashIsIgnored`.
6. `AppTest.postToTotalIsNoRouteOverHttp` (added; checks 404, `Content-Type` and `{"error":"no route"}`) and `CustomerControllerTest.postToTotalIsNoRoute`.
7. `AppTest.otherCustomerPathsAreNoRouteOverHttp` (added; `/customers/c1` and `/customers`) and `CustomerControllerTest.otherCustomerPathsAreNoRoute`.
8. `ArchitectureTest`, unchanged: both rules (`LAYERS` and `DOMAIN_IS_PLAIN`) pass.
9. The existing `OrderControllerTest` and `OrderServiceTest` still pass. `AppTest.ordersRoutesStillServed` checks `POST /orders` and `GET /orders` over HTTP. `AppTest.getOrderByIdStillServed` (added) checks `GET /orders/ord-1` and the 404 for an unknown order.

## Result
`mvn -q -f examples/java-service/pom.xml test` passed: 25 tests, 0 failures, 0 errors, 0 skipped. Per test class:
- `ArchitectureTest`: 2
- `AppTest`: 9
- `CustomerControllerTest`: 7
- `OrderControllerTest`: 3
- `OrderServiceTest`: 4

My first attempt was blocked because it added `; echo EXIT=$?` after the Maven command. That needs approval, and this session can't grant it. I ran the plain Maven command instead and read the counts from the files in `target/surefire-reports`.
