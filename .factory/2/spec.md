# Spec: POST /orders accepts zero or negative quantity

## Summary
Right now `POST /orders` stores an order whose `quantity` is zero or negative and answers `201 Created`. After this change, the service rejects any order with `quantity` below 1. The response is `400 Bad Request` with a JSON error body, and nothing is stored. The rule is enforced by `order.Service.Create`, so it applies through HTTP and through the public Go function alike.

## Acceptance criteria
1. Given an empty service, when a client sends `POST /orders` with body `{"customer":"c1","sku":"A","quantity":-3}`, then the response status is `400`, `Content-Type` is `application/json`, and the body is a JSON object with a non-empty `"error"` string field.
2. Given an empty service, when a client sends `POST /orders` with body `{"customer":"c1","sku":"A","quantity":0}`, then the response status is `400` and the body is a JSON object with a non-empty `"error"` string field.
3. Given an empty service, when a client sends `POST /orders` with body `{"customer":"c1","sku":"A"}` (no `quantity`, so it decodes to 0), then the response status is `400`.
4. Given an empty service, when a client sends `POST /orders` with body `{"customer":"c1","sku":"A","quantity":-3}` and then sends `GET /orders`, then the `GET` response is `200` with a JSON array of length 0.
5. Given an empty service, when a client sends `POST /orders` with body `{"customer":"c1","sku":"A","quantity":-3}` and then `GET /orders/ord-1`, then the `GET` response is `404`.
6. Given an empty service, when a client sends `POST /orders` with body `{"customer":"c1","sku":"A","quantity":0}` and then `{"customer":"c1","sku":"A","quantity":1}`, then the second response is `201` and its body contains `"id":"ord-1"` and `"quantity":1`. The rejected request did not use up an ID.
7. Given an empty service, when a client sends `POST /orders` with body `{"customer":"c1","sku":"A","quantity":2}`, then the response is still `201` with `"id":"ord-1"`, and `GET /orders/ord-1` returns `200` with `"quantity":2`. Valid orders behave as before.
8. Given `svc := order.NewService(store.NewMemory())`, when `svc.Create(ctx, order.Order{Customer: "c1", SKU: "A", Quantity: q})` is called with `q` set to `0`, `-1` and `-3`, then each call returns an error for which `errors.Is(err, order.ErrInvalid)` is true, and `svc.List(ctx)` then returns an empty slice.
9. Given the same service, when `svc.Create(ctx, order.Order{Customer: "c1", SKU: "A", Quantity: 1})` is called, then it returns a nil error and an order with `Quantity == 1` and a non-empty `ID`.
10. The existing checks keep working: an order missing `customer` or `sku` still returns `order.ErrInvalid` / HTTP `400`, and malformed JSON (`{`) still returns `400`.
11. `golangci-lint` with the existing `.golangci.yml` passes. `httpapi` does not import `store`, and `order` does not import `httpapi`, `store` or `net/http`.

## Out of scope
- An upper limit on `quantity`.
- Non-integer or non-numeric `quantity` values such as `1.5` or `"3"`. These already fail JSON decoding and return `400 "malformed JSON"`, and that stays as it is.
- Different error messages or error codes for each failing field, and changing the error body format (`{"error": "..."}`).
- Changing validation of `customer` or `sku`, or of the `GET` endpoints.
- Cleaning up orders with bad quantities that are already stored. Storage is in memory only, so none survive a restart.

## Open questions
- The issue asks for "an error message" but does not give the text. I assume the existing invalid-order response is enough: `400` with `{"error":"invalid order"}`, the text of `order.ErrInvalid`. A more specific message that still satisfies `errors.Is(err, order.ErrInvalid)` would also be fine. The criteria only require a non-empty `"error"` string.
- I assume a missing `quantity` field should be rejected too, because it decodes to 0 and the issue says "Quantity must be at least 1".

STATUS: READY
