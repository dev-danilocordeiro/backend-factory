# Spec: Add GET /healthz with the build version

## Summary
The service gets a new endpoint, `GET /healthz`, so load balancers can check whether it is up. It always responds `200 OK` with the JSON body `{"status":"ok","version":"<version>"}`. The version comes from a package-level string variable that the build can set with `go build -ldflags "-X <import path>.<Name>=<value>"`. If the build does not set it, the version is `"dev"`.

## Acceptance criteria
1. **Given** the handler returned by `httpapi.NewHandler(order.NewService(store.NewMemory()))`, built without any `-ldflags -X` override, **when** a client sends `GET /healthz` with no body, **then** the response status is `200`, the `Content-Type` header is `application/json`, and the body decodes as JSON to exactly `{"status":"ok","version":"dev"}`.
2. **Given** the service binary was built with `-ldflags "-X <version variable>=1.2.3"` (or a test sets that exported package-level string variable to `"1.2.3"` before calling `NewHandler`), **when** a client sends `GET /healthz`, **then** the response status is `200` and the body decodes to exactly `{"status":"ok","version":"1.2.3"}`.
3. **Given** a fresh handler with no orders created, **when** a client sends `GET /healthz`, **then** the response is `200` without any call to the order service or storage. The health check must work even when no orders exist.
4. **Given** the handler, **when** a client sends `POST /healthz`, **then** the response status is `405 Method Not Allowed`. This is the standard method-pattern behaviour of `net/http.ServeMux`.
5. **Given** the existing routes, **when** a client sends `POST /orders` with `{"customer":"c1","sku":"A","quantity":2}` and then `GET /orders/ord-1`, **then** they behave as before (`201` with `"id":"ord-1"`, then `200` with `"sku":"A"`), and all existing tests in `internal/httpapi` and `internal/order` still pass.
6. **Given** the repository's `.golangci.yml`, **when** `golangci-lint run` runs in `examples/go-service`, **then** there are no new findings. In particular, `internal/httpapi` still does not import `internal/store` in non-test files, and `internal/order` does not import `net/http`, `internal/httpapi`, or `internal/store`.

## Out of scope
- Real readiness or liveness checks, such as checking storage or dependencies. The endpoint always reports `"ok"`.
- Other build metadata such as commit SHA, build time, or Go version.
- A separate readiness endpoint (for example `/readyz`) or a metrics endpoint.
- Changes to build scripts, Dockerfiles, CI, or Makefiles that pass `-ldflags`. None exist in the service directory today.
- Auth, rate limiting, or caching headers on `/healthz`.

## Open questions
- **Where the version variable lives and what it is called.** The issue doesn't say. I assumed it is an exported package-level `string` variable whose zero value means "dev", or that is initialised to `"dev"`. It could sit in `internal/httpapi` or in a small package that `httpapi` can import without breaking the depguard rules. Its full import path and name must be documented, for example in a doc comment, so the build can target it with `-X`. Acceptance criterion 2 refers to it generically for this reason.
- **Whether an empty string counts as "not set".** I assumed that building with `-X ...=` (an empty value) also reports `"dev"`. The issue only says "when it is not set".

STATUS: READY
