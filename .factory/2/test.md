# Tests

The implementer's tests already covered criteria 1–10. I added two import-check tests for criterion 11. All tests pass. I did not run `golangci-lint`, so the lint part of criterion 11 is still unchecked.

## Coverage of acceptance criteria
1. `TestCreateNegativeQuantityIs400` (`internal/httpapi/handler_test.go`): checks for status 400, `Content-Type: application/json` and a non-empty `"error"` field.
2. `TestCreateZeroQuantityIs400`: checks for status 400 and a non-empty `"error"` field.
3. `TestCreateMissingQuantityIs400`: checks for status 400.
4. `TestRejectedOrderNotListed`: checks that `GET /orders` returns 200 with `[]`, decoded as an array of length 0.
5. `TestRejectedOrderNotFetchable`: checks that `GET /orders/ord-1` returns 404.
6. `TestRejectedOrderDoesNotConsumeID`: checks that the second POST returns 201 with `"id":"ord-1"` and `"quantity":1`.
7. `TestCreateThenGet`: checks that the POST returns 201 with `ord-1`, and that the GET returns 200 with `"quantity":2`.
8. `TestCreateRejectsNonPositiveQuantity` (`internal/order/order_test.go`): checks quantities 0, -1 and -3 with `errors.Is(err, order.ErrInvalid)`, and that `List` then returns an empty slice.
9. `TestCreateAcceptsQuantityOne`: checks for a nil error, `Quantity == 1` and a non-empty `ID`.
10. `TestCreateRejectsMissingFields` (service returns `ErrInvalid`), `TestCreateMissingCustomerOrSKUIs400` (HTTP 400) and `TestMalformedJSONIs400` (`{` returns 400).
11. Partly covered.
    - **Import rules:** covered by two new tests. `TestOrderImports` (`internal/order/imports_test.go`) fails if `order` imports `net/http`, `httpapi` or `store`. `TestHTTPAPIDoesNotImportStore` (`internal/httpapi/imports_test.go`) fails if `httpapi` imports `store`. Both read the imports of the package's non-test source files.
    - **`golangci-lint`:** not covered. My command that looked for the tool was blocked because it needed approval and this session cannot grant it, so I never ran the linter. I ran `go vet` instead, and it passed.

## Result
`go -C examples/go-service test ./...` passed (`internal/httpapi` ok, `internal/order` ok). `go -C examples/go-service vet ./...` also passed.
