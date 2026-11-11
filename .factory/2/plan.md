# Plan

## Files
- `examples/go-service/internal/order/order.go`: in `Service.Create`, also return `ErrInvalid` when `o.Quantity < 1`. The check runs before `s.repo.Save`, so nothing is stored and no ID is used up.
- `examples/go-service/internal/httpapi/handler.go`: in the `GET /orders` handler, swap a nil result from `svc.List` for an empty `[]order.Order{}`. Without this, an empty list is encoded as `null` instead of `[]`, which fails criterion 4. The memory store returns `append([]order.Order(nil), ...)`, which is nil when there are no orders.
- `examples/go-service/internal/order/order_test.go`: add domain tests for quantity validation.
- `examples/go-service/internal/httpapi/handler_test.go`: add HTTP tests for quantity rejection, no storage after rejection, ID numbering, and missing fields.

## Steps
1. In `order.go`, change the guard in `Create` to:
   `if strings.TrimSpace(o.SKU) == "" || strings.TrimSpace(o.Customer) == "" || o.Quantity < 1 { return Order{}, ErrInvalid }`
   Return `ErrInvalid` itself, not a wrapped error. The HTTP body then stays `{"error":"invalid order"}`, as the spec's open question assumes.
2. In `handler.go`, in the `GET /orders` handler, after the error check add `if out == nil { out = []order.Order{} }` before `writeJSON`.
3. Add the tests listed below to `order_test.go`.
4. Add the tests listed below to `handler_test.go`. Add `encoding/json` to its imports so the tests can decode bodies. Test files may import `store` because depguard excludes them with `!$test`, and the existing tests already do this.
5. Run `go test ./...` and `golangci-lint run` from `examples/go-service`.

## Tests
1. `internal/httpapi/handler_test.go` · `TestCreateNegativeQuantityIs400`: POSTs `quantity:-3`. Checks the status is 400, `Content-Type` is `application/json`, and the body decodes to a `map[string]string` whose `"error"` is not empty.
2. `internal/httpapi/handler_test.go` · `TestCreateZeroQuantityIs400`: POSTs `quantity:0`. Checks the status is 400 and the decoded `"error"` is not empty.
3. `internal/httpapi/handler_test.go` · `TestCreateMissingQuantityIs400`: POSTs `{"customer":"c1","sku":"A"}` and checks the status is 400.
4. `internal/httpapi/handler_test.go` · `TestRejectedOrderNotListed`: POSTs `quantity:-3`, then sends `GET /orders`. Checks the status is 200 and the body decodes to a `[]order.Order` of length 0. It also checks the trimmed body is `[]`, so a `null` body fails.
5. `internal/httpapi/handler_test.go` · `TestRejectedOrderNotFetchable`: POSTs `quantity:-3`, then sends `GET /orders/ord-1` and checks the status is 404.
6. `internal/httpapi/handler_test.go` · `TestRejectedOrderDoesNotConsumeID`: POSTs `quantity:0`, then `quantity:1`. Checks the second status is 201 and the body contains `"id":"ord-1"` and `"quantity":1`.
7. `internal/httpapi/handler_test.go` · `TestCreateThenGet`: the existing test, extended so the GET also checks the body contains `"quantity":2`.
8. `internal/order/order_test.go` · `TestCreateRejectsNonPositiveQuantity`: a table of `0`, `-1` and `-3`. Each call must satisfy `errors.Is(err, order.ErrInvalid)`. Afterwards `svc.List` must return `len == 0`.
9. `internal/order/order_test.go` · `TestCreateAcceptsQuantityOne`: checks the error is nil, `Quantity == 1` and `ID != ""`.
10. Domain: `internal/order/order_test.go` · `TestCreateRejectsMissingFields`, updated to give its cases `Quantity: 1` so they fail only on the missing field. HTTP: new `internal/httpapi/handler_test.go` · `TestCreateMissingCustomerOrSKUIs400`, which uses `quantity:1` and leaves out `customer` in one case and `sku` in the other, expecting 400 for each. Malformed JSON: the existing `TestMalformedJSONIs400`.
11. Run `golangci-lint run` in `examples/go-service` with the existing `.golangci.yml`. Neither changed non-test file adds an import.

## Layering
- The quantity rule lives in the `order` domain layer, in `Service.Create`, so it applies to HTTP and to direct Go callers alike, as the spec requires. It needs no new imports, so `order` still imports no `httpapi`, `store` or `net/http`.
- The nil-to-empty-slice change lives in the `httpapi` transport layer, because it is about the JSON format. It uses only `order.Order`, so `httpapi` still does not import `store`.
- `store` is not changed.
- `.golangci.yml` and build tooling are not touched.

## Risks
- Any client or Go caller that currently creates orders with quantity 0 or below, including by leaving out `quantity`, will now get `ErrInvalid` / 400. This is the intended change. Inside the repo, only `TestCreateRejectsMissingFields` relies on zero quantity, and it is updated.
- `GET /orders` on an empty service now returns `[]` instead of `null`. A client that checks for `null` would see a different body, but both mean "no orders", and the spec requires an array.
- Rejected orders still get the generic `"invalid order"` message, so clients cannot tell a bad quantity from a missing field. The spec accepts this.
