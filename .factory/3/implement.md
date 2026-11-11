# Implementation

`GET /orders` now takes optional `page` and `size` query parameters. It returns `{"items": [...], "page": n, "size": n, "total": n}`, and bad `page` or `size` values get a 400 with an `error` body. The build passes: all 29 tests pass and checkstyle reports no violations.

## Changes
- `examples/java-service/src/main/java/com/example/orders/domain/InvalidPageException.java`: new exception class, written like `InvalidOrderException`, with only a message constructor.
- `examples/java-service/src/main/java/com/example/orders/domain/OrderPage.java`: new record `OrderPage(items, page, size, total)`; it keeps its own copy of `items`.
- `examples/java-service/src/main/java/com/example/orders/service/OrderService.java`: added `DEFAULT_PAGE` (0), `DEFAULT_SIZE` (20) and `MAX_SIZE` (100). Replaced `list()` with `list(int page, int size)`, which checks the range and returns one page. The page offset is computed as a `long`, so a huge `page` can't overflow.
- `examples/java-service/src/main/java/com/example/orders/api/OrderController.java`:
  - Added a 4-argument `route` that takes the raw query string; the old 3-argument `route` passes `null` to it.
  - `handle` now passes the request's raw query.
  - The query is parsed and URL-decoded; when a key repeats, the last value wins, and keys other than `page` and `size` are ignored.
  - `page` and `size` must match `-?\d+`, so `abc`, `ten`, `1.5` and empty values return 400. Negative or out-of-range numbers are rejected by the service and also return 400.
  - A malformed `%` escape in the query returns 400 instead of 500.
- `examples/java-service/src/test/java/com/example/orders/api/OrderControllerTest.java`: updated `createThenListAndGet` for the new response shape and added tests for acceptance criteria 1–15. Criterion 13 is one parameterized test covering its 5 inputs. The tests check the JSON produced by Jackson's `valueToTree`.
- `examples/java-service/src/test/java/com/example/orders/service/OrderServiceTest.java`: added `listPagesInCreationOrder`, `listRejectsInvalidPageOrSize` and `hugePageDoesNotOverflow`.

## Commands run
- `mvn -q -f examples/java-service/pom.xml verify` plus a `checkstyle:check` step, with a shell variable to capture its exit code: **blocked**, not run. The session can't approve commands containing shell variables.
- `mvn -q -f examples/java-service/pom.xml verify checkstyle:check`: **passed**, with no errors and no checkstyle violations.
- `mvn -f examples/java-service/pom.xml verify checkstyle:check`, filtered to show test counts: **passed**. 29 tests ran with 0 failures (OrderControllerTest 22, OrderServiceTest 5, ArchitectureTest 2). Result: `BUILD SUCCESS`.
- A `grep` I started in the wrong directory was **blocked** because the path was outside the worktree. I read the files with the Read tool instead.

## Deviations from the plan
None. Small notes:
- The size error message is built from `MAX_SIZE` ("size must be between 1 and 100").
- The pom doesn't run checkstyle during `verify`, so I added `checkstyle:check` to the command to cover it.
