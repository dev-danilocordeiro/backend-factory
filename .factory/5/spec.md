# Spec: Make the order service faster

## Summary
The issue asks for the order service to be faster, but it gives no detail beyond "feels slow". It does not name an endpoint, a workload, a measured latency or throughput, or a target. The service today is a small in-memory app (`OrderController` → `OrderService` → `InMemoryOrderRepository`, using the JDK `HttpServer`). Every repository method is `synchronized`, and `GET /orders` returns a full copy of every stored order. Without a named bottleneck and a measurable target, no change can be shown to meet the request.

## Acceptance criteria
1. Given the current service, when `POST /orders`, `GET /orders` and `GET /orders/{id}` are called with the inputs the existing tests use, then the status codes and JSON bodies are the same as today (for example, `GET /orders/ord-999` returns `404` with `{"error":"order not found"}`, and a valid POST returns `201` with id `ord-1` on an empty repository). This only guards against regressions.
2. *(Cannot be written without guessing.)* A performance criterion needs four things: an operation (e.g. `GET /orders`), a load (e.g. N stored orders, M concurrent clients), a metric (p95 latency or requests per second) and a target value. The issue gives none of them.

## Out of scope
- Adding a database, cache, connection pool or any other infrastructure.
- Changing the HTTP API shape, for example adding pagination to `GET /orders`, unless the author asks for it.
- Adding a benchmarking or load-testing harness that the issue does not ask for.

## Open questions
- Which operation is slow: create, get-by-id, or list all?
- Under what conditions: how many stored orders, and how many concurrent requests?
- What does "faster" mean in numbers: p50 or p95 latency, or throughput, and what is the target?
- Is it acceptable to change the API (for example, paginate `GET /orders`) or the repository's concurrency model (for example, drop the global `synchronized` lock)?
- Is there a profile, trace or reproduction that shows where the time goes?

Without these answers, any performance criterion would be invented rather than taken from the issue, so a human needs to clarify the request before implementation.

STATUS: NEEDS_HUMAN
