# Implementation

`POST /orders` now rejects any order with `quantity` below 1. The response is `400` with `{"error":"invalid order"}`. Nothing is stored and no ID is used up. `GET /orders` on an empty service now returns `[]` instead of `null`. Build, tests, lint and formatting all pass.

## Changes
- `internal/order/order.go`: `Service.Create` now also returns `ErrInvalid` when `o.Quantity < 1`, before anything is saved.
- `internal/httpapi/handler.go`: in `GET /orders`, a nil list from `svc.List` is replaced with `[]order.Order{}`, so the body is `[]` instead of `null`.
- `internal/order/order_test.go`: `TestCreateRejectsMissingFields` cases now have `Quantity: 1`. Added `TestCreateRejectsNonPositiveQuantity` (0, -1, -3, then checks the list is empty) and `TestCreateAcceptsQuantityOne`.
- `internal/httpapi/handler_test.go`:
  - Added the `encoding/json` import and an `errorField` helper that reads the `"error"` field from a response body.
  - `TestCreateThenGet` now also checks for `"quantity":2`.
  - Added `TestCreateNegativeQuantityIs400`, `TestCreateZeroQuantityIs400`, `TestCreateMissingQuantityIs400`, `TestRejectedOrderNotListed`, `TestRejectedOrderNotFetchable`, `TestRejectedOrderDoesNotConsumeID` and `TestCreateMissingCustomerOrSKUIs400`.

## Commands run
- `go -C examples/go-service build ./...`: passed
- `go -C examples/go-service test ./...`: passed (`httpapi` and `order` ok)
- `cd examples/go-service && golangci-lint run ./...`: passed, 0 issues
- `gofmt -l examples/go-service`: passed, no files listed
- A first combined command (a Python edit script followed by build, test and lint) was blocked by the permission check and did not run. I made the same edits with the Edit tool and ran the commands above one at a time.

## Deviations from the plan
None.
