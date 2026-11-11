# Spec: Show the customer total on the orders screen

## Summary
Add a new read-only endpoint, `GET /customers/{customerId}/total`. It returns how much one customer has spent across all their stored orders, in cents. The response is `200` with a JSON body `{"customerId":"<id>","totalCents":<sum>}`. `<sum>` is the sum of `quantity × unitPriceCents` over every order with that `customerId`. A customer with no orders gets `totalCents` 0, not a 404.

## Acceptance criteria
1. Given these orders were created through `POST /orders`: `{"customerId":"c1","sku":"A","quantity":2,"unitPriceCents":100}` and `{"customerId":"c1","sku":"B","quantity":3,"unitPriceCents":50}`, When a client sends `GET /customers/c1/total`, Then the response status is `200`, `Content-Type` is `application/json`, and the body is `{"customerId":"c1","totalCents":350}`.
2. Given an order for customer `c1` (`quantity` 2, `unitPriceCents` 100) and an order for customer `c2` (`quantity` 1, `unitPriceCents` 999), When a client sends `GET /customers/c1/total`, Then the body is `{"customerId":"c1","totalCents":200}`. Orders from other customers are not counted.
3. Given no orders exist for customer `c9` (whether or not other customers have orders), When a client sends `GET /customers/c9/total`, Then the response status is `200` and the body is `{"customerId":"c9","totalCents":0}`.
4. Given one order for customer `c1` with `quantity` 1 and `unitPriceCents` 12345, When a client sends `GET /customers/c1/total`, Then the body is `{"customerId":"c1","totalCents":12345}`. The value is a JSON integer, not a string or a decimal.
5. Given any orders exist, When a client sends `GET /customers/c1/total/` (with a trailing slash), Then the response is the same as for `GET /customers/c1/total`. This matches how trailing slashes are handled for `/orders` today.
6. Given the service is running, When a client sends `POST /customers/c1/total`, Then the response status is `404` with body `{"error":"no route"}`. This is the same "no route" response the service already gives for unknown routes.
7. Given the service is running, When a client sends `GET /customers/c1` or `GET /customers`, Then the response status is `404` with body `{"error":"no route"}`.
8. Given the change is in place, When the build runs `ArchitectureTest`, Then the `LAYERS` and `DOMAIN_IS_PLAIN` rules still pass. In particular, no class in the `api` package depends on the `repository` package.
9. Given the change is in place, When the existing `/orders` requests are sent (`POST /orders`, `GET /orders`, `GET /orders/{id}`), Then they behave exactly as before. The existing tests in `OrderControllerTest` and `OrderServiceTest` still pass.

## Out of scope
- Listing a customer's orders, paginating them, or filtering totals by date, SKU or status.
- Any customer entity, customer registry or customer-existence check. An unknown customer ID is treated as "no orders" and gets total 0.
- Currency formatting, or any unit other than integer cents.
- Persistence or caching changes. Totals are computed from whatever orders the existing repository holds.
- Changes to `ArchitectureTest.java`, which agents may not edit.

## Open questions
- The issue suggests the controller "can just read all orders from the repository and sum them". `ArchitectureTest` forbids that: the `Repository` layer may only be accessed by the `Service` layer. I treated the hint as non-binding implementation advice, not a requirement. Criterion 8 requires the architecture rules to keep passing, so the total has to come through the service layer.
- I assumed a blank `customerId` path segment, as in `GET /customers//total`, needs no special handling beyond the existing "no route" 404. The issue does not mention it.
- I assumed `totalCents` is a 64-bit integer, matching the existing `Order.totalCents()` return type of `long`. Overflow beyond that range is not handled.

STATUS: READY
