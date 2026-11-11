# Spec: Add GET /healthz with the build version

## Summary
Load balancers need a health endpoint, so the service gets a new `GET /healthz` route. It returns `200 OK` with the JSON body `{"status":"ok","version":"<version>"}`. The version comes from a package-level string variable that the build can set with `go build -ldflags "-X <import path>.<name>=<value>"`. If the build does not set it, the version is `"dev"`. The existing `/orders` routes do not change.

## Acceptance criteria
1. Given the service is built without any `-X` ldflag for the version variable, when a client sends `GET /healthz`, then the response status is `200` and the body decodes to exactly `{"status":"ok","version":"dev"}`.
2. Given a running handler, when a client sends `GET /healthz`, then the `Content-Type` response header is `application/json`. This matches the other routes.
3. Given the service is built with `go build -ldflags "-X <version variable's full import path>.<name>=1.2.3" ./cmd/server`, when a client sends `GET /healthz` to that binary, then the response status is `200` and the body decodes to exactly `{"status":"ok","version":"1.2.3"}`.
4. Given the version variable is a package-level `string` variable, when someone reads the code or its doc comment, then they can find its full import path and name, which is what they pass to `-X`.
5. Given a handler built with `httpapi.NewHandler(order.NewService(store.NewMemory()))` and an empty store, when a client sends `GET /healthz`, then the response is `200`. The health check does not depend on any orders existing.
6. Given the new endpoint is in place, when the existing tests (`TestCreateThenGet`, `TestGetUnknownIs404`, `TestMalformedJSONIs400`) and `golangci-lint` with the current `.golangci.yml` are run, then all of them pass. In particular, non-test code in `internal/httpapi` still does not import `internal/store`.

## Out of scope
- Readiness or liveness checks against dependencies, such as the store. The endpoint always reports `"ok"` while the process is serving HTTP.
- Other build metadata, such as commit SHA, build time or Go version.
- Methods other than `GET` on `/healthz`. These get the router's default behaviour; the change adds no special handling.
- Adding a Makefile, Dockerfile or CI step that sets the version during the build.
- Authentication or rate limiting on the endpoint.

## Open questions
- The issue does not say which package should hold the version variable or what to call it. I assume one string variable in the service's own module. Its default value is `"dev"`, and its full path must be documented for `-X`, as criterion 4 requires. The implementer chooses the package and the name.
- I assume the response has exactly the two fields `status` and `version`, with no extra fields.

STATUS: READY
